/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.rx.event.impl;

import org.hibernate.*;
import org.hibernate.cache.spi.access.CollectionDataAccess;
import org.hibernate.cache.spi.access.EntityDataAccess;
import org.hibernate.cache.spi.access.SoftLock;
import org.hibernate.engine.internal.CascadePoint;
import org.hibernate.engine.spi.*;
import org.hibernate.event.internal.EvictVisitor;
import org.hibernate.event.service.spi.DuplicationStrategy;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.RefreshEvent;
import org.hibernate.event.spi.RefreshEventListener;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.metamodel.spi.MetamodelImplementor;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.rx.engine.impl.Cascade;
import org.hibernate.rx.engine.impl.CascadingActions;
import org.hibernate.rx.event.spi.RxRefreshEventListener;
import org.hibernate.rx.util.impl.RxUtil;
import org.hibernate.type.CollectionType;
import org.hibernate.type.CompositeType;
import org.hibernate.type.Type;

import java.io.Serializable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Defines the default refresh event listener used by hibernate for refreshing entities
 * in response to generated refresh events.
 *
 * @author Steve Ebersole
 */
public class DefaultRxRefreshEventListener implements RefreshEventListener, RxRefreshEventListener {
	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( DefaultRxRefreshEventListener.class );

	public CompletionStage<Void> rxOnRefresh(RefreshEvent event) throws HibernateException {
		return rxOnRefresh( event, new IdentityHashMap( 10 ) );
	}

	@Override
	public void onRefresh(RefreshEvent event) throws HibernateException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void onRefresh(RefreshEvent event, Map refreshedAlready) throws HibernateException {
		throw new UnsupportedOperationException();
	}

	/**
	 * Handle the given refresh event.
	 *
	 * @param event The refresh event to be handled.
	 * @return
	 */
	public CompletionStage<Void> rxOnRefresh(RefreshEvent event, Map refreshedAlready) {

		final EventSource source = event.getSession();
		boolean isTransient;
		if ( event.getEntityName() != null ) {
			isTransient = !source.contains( event.getEntityName(), event.getObject() );
		}
		else {
			isTransient = !source.contains( event.getObject() );
		}
		final PersistenceContext persistenceContext = source.getPersistenceContextInternal();
		if ( persistenceContext.reassociateIfUninitializedProxy( event.getObject() ) ) {
			if ( isTransient ) {
				source.setReadOnly( event.getObject(), source.isDefaultReadOnly() );
			}
			return RxUtil.nullFuture();
		}

		final Object object = persistenceContext.unproxyAndReassociate( event.getObject() );

		if ( refreshedAlready.containsKey( object ) ) {
			LOG.trace( "Already refreshed" );
			return RxUtil.nullFuture();
		}

		final EntityEntry e = persistenceContext.getEntry( object );
		final EntityPersister persister;
		final Serializable id;

		if ( e == null ) {
			persister = source.getEntityPersister(
					event.getEntityName(),
					object
			); //refresh() does not pass an entityName
			id = persister.getIdentifier( object, event.getSession() );
			if ( LOG.isTraceEnabled() ) {
				LOG.tracev(
						"Refreshing transient {0}", MessageHelper.infoString(
						persister,
						id,
						source.getFactory()
				)
				);
			}
			final EntityKey key = source.generateEntityKey( id, persister );
			if ( persistenceContext.getEntry( key ) != null ) {
				throw new PersistentObjectException(
						"attempted to refresh transient instance when persistent instance was already associated with the Session: " +
								MessageHelper.infoString( persister, id, source.getFactory() )
				);
			}
		}
		else {
			if ( LOG.isTraceEnabled() ) {
				LOG.tracev(
						"Refreshing ", MessageHelper.infoString(
						e.getPersister(),
						e.getId(),
						source.getFactory()
				)
				);
			}
			if ( !e.isExistsInDatabase() ) {
				throw new UnresolvableObjectException(
						e.getId(),
						"this instance does not yet exist as a row in the database"
				);
			}

			persister = e.getPersister();
			id = e.getId();
		}

		// cascade the refresh prior to refreshing this entity
		refreshedAlready.put( object, object );
		CompletionStage<Void> cascade =
				new Cascade(CascadingActions.REFRESH,
						CascadePoint.BEFORE_REFRESH,
						persister,
						object,
						source)
						.cascade(refreshedAlready);

		if ( e != null ) {
			final EntityKey key = source.generateEntityKey( id, persister );
			persistenceContext.removeEntity( key );
			if ( persister.hasCollections() ) {
				new EvictVisitor( source, object ).process( object, persister );
			}
		}

		if ( persister.canWriteToCache() ) {
			Object previousVersion = null;
			if ( persister.isVersionPropertyGenerated() ) {
				// we need to grab the version value from the entity, otherwise
				// we have issues with generated-version entities that may have
				// multiple actions queued during the same flush
				previousVersion = persister.getVersion( object );
			}
			final EntityDataAccess cache = persister.getCacheAccessStrategy();
			final Object ck = cache.generateCacheKey(
					id,
					persister,
					source.getFactory(),
					source.getTenantIdentifier()
			);
			final SoftLock lock = cache.lockItem( source, ck, previousVersion );
			cache.remove( source, ck );
			source.getActionQueue().registerProcess( (success, session) -> cache.unlockItem( session, ck, lock ) );
		}

		evictCachedCollections( persister, id, source );

		String previousFetchProfile = source.getLoadQueryInfluencers().getInternalFetchProfile();
		source.getLoadQueryInfluencers().setInternalFetchProfile( "refresh" );


		// Handle the requested lock-mode (if one) in relation to the entry's (if one) current lock-mode

		LockOptions lockOptionsToUse = event.getLockOptions();

		final LockMode requestedLockMode = lockOptionsToUse.getLockMode();
		final LockMode postRefreshLockMode;

		if ( e != null ) {
			final LockMode currentLockMode = e.getLockMode();
			if ( currentLockMode.greaterThan( requestedLockMode ) ) {
				// the requested lock-mode is less restrictive than the current one
				//		- pass along the current lock-mode (after accounting for WRITE)
				lockOptionsToUse = LockOptions.copy( event.getLockOptions(), new LockOptions() );
				if ( currentLockMode == LockMode.WRITE ||
						currentLockMode == LockMode.PESSIMISTIC_WRITE ||
						currentLockMode == LockMode.PESSIMISTIC_READ ) {
					// our transaction should already hold the exclusive lock on
					// the underlying row - so READ should be sufficient.
					//
					// in fact, this really holds true for any current lock-mode that indicates we
					// hold an exclusive lock on the underlying row - but we *need* to handle
					// WRITE specially because the Loader/Locker mechanism does not allow for WRITE
					// locks
					lockOptionsToUse.setLockMode( LockMode.READ );

					// and prepare to reset the entry lock-mode to the previous lock mode after
					// the refresh completes
					postRefreshLockMode = currentLockMode;
				}
				else {
					lockOptionsToUse.setLockMode( currentLockMode );
					postRefreshLockMode = null;
				}
			}
			else {
				postRefreshLockMode = null;
			}
		}
		else {
			postRefreshLockMode = null;
		}

		final CompletionStage<Optional<Object>> stage =
				(CompletionStage<Optional<Object>>) persister.load( id, object, lockOptionsToUse, source );

		return cascade.thenCompose(v -> stage).thenAccept(option -> {
			Object result = option.get();

			if (result != null) {
				// apply `postRefreshLockMode`, if needed
				if (postRefreshLockMode != null) {
					// if we get here, there was a previous entry and we need to re-set its lock-mode
					//		- however, the refresh operation actually creates a new entry, so get it
					persistenceContext.getEntry(result).setLockMode(postRefreshLockMode);
				}

				// Keep the same read-only/modifiable setting for the entity that it had before refreshing;
				// If it was transient, then set it to the default for the source.
				if (!persister.isMutable()) {
					// this is probably redundant; it should already be read-only
					source.setReadOnly(result, true);
				} else {
					source.setReadOnly(result, (e == null ? source.isDefaultReadOnly() : e.isReadOnly()));
				}
			}
			source.getLoadQueryInfluencers().setInternalFetchProfile(previousFetchProfile);

			UnresolvableObjectException.throwIfNull(result, id, persister.getEntityName());

		});

	}

	private void evictCachedCollections(EntityPersister persister, Serializable id, EventSource source) {
		evictCachedCollections( persister.getPropertyTypes(), id, source );
	}

	private void evictCachedCollections(Type[] types, Serializable id, EventSource source)
			throws HibernateException {
		final ActionQueue actionQueue = source.getActionQueue();
		final SessionFactoryImplementor factory = source.getFactory();
		final MetamodelImplementor metamodel = factory.getMetamodel();
		for ( Type type : types ) {
			if ( type.isCollectionType() ) {
				CollectionPersister collectionPersister = metamodel.collectionPersister( ( (CollectionType) type ).getRole() );
				if ( collectionPersister.hasCache() ) {
					final CollectionDataAccess cache = collectionPersister.getCacheAccessStrategy();
					final Object ck = cache.generateCacheKey(
						id,
						collectionPersister,
						factory,
						source.getTenantIdentifier()
					);
					final SoftLock lock = cache.lockItem( source, ck, null );
					cache.remove( source, ck );
					actionQueue.registerProcess( (success, session) -> cache.unlockItem( session, ck, lock ) );
				}
			}
			else if ( type.isComponentType() ) {
				CompositeType actype = (CompositeType) type;
				evictCachedCollections( actype.getSubtypes(), id, source );
			}
		}
	}

	public static class EventContextManagingDeleteEventListenerDuplicationStrategy implements DuplicationStrategy {

		public static final DuplicationStrategy INSTANCE = new DefaultRxRefreshEventListener.EventContextManagingDeleteEventListenerDuplicationStrategy();

		private EventContextManagingDeleteEventListenerDuplicationStrategy() {
		}

		@Override
		public boolean areMatch(Object listener, Object original) {
			if ( listener instanceof DefaultRxRefreshEventListener && original instanceof RefreshEventListener) {
				return true;
			}

			return false;
		}

		@Override
		public Action getAction() {
			return Action.REPLACE_ORIGINAL;
		}
	}
}
