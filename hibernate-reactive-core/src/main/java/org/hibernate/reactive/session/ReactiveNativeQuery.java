package org.hibernate.reactive.session;

import org.hibernate.LockMode;
import org.hibernate.transform.ResultTransformer;

/**
 * @author Gavin King
 */
public interface ReactiveNativeQuery<R> extends ReactiveQuery<R> {
	ReactiveNativeQuery<R> setResultTransformer(ResultTransformer resultTransformer);
	ReactiveNativeQuery<R> addEntity(String alias, String name, LockMode read);
	ReactiveNativeQuery<R> setResultSetMapping(String resultSetMapping);
}