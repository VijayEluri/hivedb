package org.hivedb.hibernate;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;

import org.hibernate.Criteria;
import org.hibernate.EmptyInterceptor;
import org.hibernate.Interceptor;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.shards.util.Lists;
import org.hivedb.Hive;
import org.hivedb.HiveRuntimeException;
import org.hivedb.annotations.IndexType;
import org.hivedb.configuration.EntityConfig;
import org.hivedb.configuration.EntityIndexConfig;
import org.hivedb.configuration.EntityIndexConfigDelegator;
import org.hivedb.configuration.EntityIndexConfigImpl;
import org.hivedb.util.GeneratedInstanceInterceptor;
import org.hivedb.util.ReflectionTools;
import org.hivedb.util.functional.Amass;
import org.hivedb.util.functional.Atom;
import org.hivedb.util.functional.Filter;
import org.hivedb.util.functional.Joiner;
import org.hivedb.util.functional.Predicate;
import org.hivedb.util.functional.Transform;
import org.hivedb.util.functional.Unary;
import org.hivedb.util.functional.Transform.IdentityFunction;

public class BaseDataAccessObject implements DataAccessObject<Object, Serializable>{
	protected HiveSessionFactory factory;
	protected EntityConfig config;
	protected Class<?> clazz;
	protected Interceptor defaultInterceptor = EmptyInterceptor.INSTANCE;
	protected Hive hive;
	protected EntityIndexConfig partitionIndexEntityIndexConfig;

	public Hive getHive() {
		return hive;
	}

	public void setHive(Hive hive) {
		this.hive = hive;
	}

	public BaseDataAccessObject(EntityConfig config, Hive hive, HiveSessionFactory factory) {
		this.clazz = config.getRepresentedInterface();
		this.config = config;
		this.factory = factory;
		this.hive = hive;
		
	}
	
	public BaseDataAccessObject(EntityConfig config, Hive hive, HiveSessionFactory factory, Interceptor interceptor) {
		this(config,hive,factory);
		this.defaultInterceptor = interceptor;
	}
	
	public Serializable delete(final Serializable id) {
		SessionCallback callback = new SessionCallback() {
			public void execute(Session session) {
				Object deleted = get(id, session);
				session.delete(deleted);
			}};
		doInTransaction(callback, getSession());
		return id;
	}

	public Boolean exists(Serializable id) {
		return hive.directory().doesResourceIdExist(config.getResourceName(), id);
	}

	public Object get(final Serializable id) {
		try {
			QueryCallback query = new QueryCallback(){
				public Collection<Object> execute(Session session) {
					return Lists.newArrayList(get(id,session));
				}};
			return Atom.getFirstOrThrow(queryInTransaction(query, getSession()));
		} catch(RuntimeException e) {
			//This save us a directory hit for all cases except when requesting a non-existent id.
			if(!exists(id))
				return null; 
			else
				throw e;
		}
	}
	
	private Object get(Serializable id, Session session) {
		Object fetched = session.get(getRespresentedClass(), id);
		return fetched;
	}
	
	public Collection<Object> findByProperty(final String propertyName, final Object propertyValue) {
		final EntityIndexConfig indexConfig = resolveEntityIndexConfig(propertyName);
		Session session = createSessionForIndex(config, indexConfig, propertyValue);
		
		QueryCallback query;
		// We use HQL to query for collection properties. Technically this is only needed
		// for primitive collections, but since we sometimes trick Hibernate into thinking our non-primitive collections
		// are primtives, we'll use this for everything for now.
		if (isComplexCollection(propertyName)) {
			 query = new QueryCallback(){
				 public Collection<Object> execute(Session session) {
					return queryWithHQL(indexConfig, session, propertyValue);
				 }};
		}
		else {
			query = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					// setResultTransformer fixes a Hibernate bug of returning duplicates when joins exist
					Criteria criteria = session.createCriteria(config.getRepresentedInterface()).setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
					addPropertyRestriction(indexConfig, criteria, propertyName, propertyValue); 
					return criteria.list();
				}};
		}
		return queryInTransaction(query, session);
	}
	
	public Collection<Object> getProperty(final String propertyName, final int firstResult, final int maxResults) {
		QueryCallback callback = new QueryCallback(){
			@SuppressWarnings("unchecked")
			public Collection<Object> execute(Session session) {
				Query query = 
					session.createQuery(
						String.format(
								"select %s from %s", 
								propertyName, 
								GeneratedInstanceInterceptor.getGeneratedClass(config.getRepresentedInterface()).getSimpleName()));
				if (maxResults > 0) {
					query.setFirstResult(firstResult);
					query.setMaxResults(maxResults);
				}
				return query.list();
			}};
		return queryInTransaction(callback, getSession());
	}
	
	protected Collection<Object> queryWithHQL(final EntityIndexConfig indexConfig, Session session, final Object propertyValue) {
		QueryCallback callback = new QueryCallback(){
			@SuppressWarnings("unchecked")
			public Collection<Object> execute(Session session) {
				Query query = session.createQuery(String.format("from %s as x where :value in elements (x.%s)",
						GeneratedInstanceInterceptor.getGeneratedClass(config.getRepresentedInterface()).getSimpleName(),
						indexConfig.getPropertyName())
						).setParameter("value", propertyValue);
				return query.list();
			}};
		return queryInTransaction(callback, session);
	}

	public Integer getCount(final String propertyName, final Object propertyValue) {
		final EntityIndexConfig indexConfig = resolveEntityIndexConfig(propertyName);
		QueryCallback query;
		Session session = createSessionForIndex(config, indexConfig, propertyValue);
		if (isComplexCollection(propertyName)) {
				query = new QueryCallback(){

					public Collection<Object> execute(Session session) {
						return queryWithHQLRowCount(indexConfig, session, propertyValue);
					}};
		}
		else {
			query = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					// setResultTransformer fixes a Hibernate bug of returning duplicates when joins exist
					Criteria criteria = session.createCriteria(config.getRepresentedInterface()).setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
					addPropertyRestriction(indexConfig, criteria, propertyName, propertyValue); 
					criteria.setProjection( Projections.rowCount() );
					return criteria.list();
				}};
		}
		return (Integer)Atom.getFirstOrThrow(queryInTransaction(query, session));
	}

	private boolean isComplexCollection(final String propertyName) {
		return ReflectionTools.isCollectionProperty(config.getRepresentedInterface(), propertyName)
			&& !ReflectionTools.isComplexCollectionItemProperty(config.getRepresentedInterface(), propertyName);
	}
	
	@SuppressWarnings("unchecked")
	private Collection<Object> queryWithHQLRowCount(EntityIndexConfig indexConfig, Session session, Object propertyValue) {
		Query query = session.createQuery(String.format("select count(%s) from %s as x where :value in elements (x.%s)",
			config.getIdPropertyName(),
			GeneratedInstanceInterceptor.getGeneratedClass(config.getRepresentedInterface()).getSimpleName(),
			indexConfig.getIndexName())
			).setParameter("value", propertyValue);
		return query.list();
	}
	
	protected EntityIndexConfig resolveEntityIndexConfig(String propertyName) {
		EntityIndexConfig indexConfig = config.getPrimaryIndexKeyPropertyName().equals(propertyName)
			? createEntityIndexConfigForPartitionIndex(config)
			: config.getEntityIndexConfig(propertyName);
		return indexConfig;
	}

	private EntityIndexConfig createEntityIndexConfigForPartitionIndex(EntityConfig entityConfig) {
		if (partitionIndexEntityIndexConfig == null)
			partitionIndexEntityIndexConfig = new EntityIndexConfigImpl(entityConfig.getRepresentedInterface(), entityConfig.getPrimaryIndexKeyPropertyName());
		return partitionIndexEntityIndexConfig;
	}

	public Collection<Object> findByProperty(final String propertyName, final Object propertyValue, final Integer firstResult, final Integer maxResults) {
		final EntityConfig entityConfig = config;
		final EntityIndexConfig indexConfig = entityConfig.getEntityIndexConfig(propertyName);
		Session session = createSessionForIndex(entityConfig, indexConfig, propertyValue);
		QueryCallback callback;
		if (isComplexCollection(propertyName)) {
			callback = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					Query query = session.createQuery(String.format("from %s as x where :value in elements (x.%s) order by x.%s asc limit %s, %s",
							GeneratedInstanceInterceptor.getGeneratedClass(entityConfig.getRepresentedInterface()).getSimpleName(),
							indexConfig.getIndexName(),
							entityConfig.getIdPropertyName(),
							firstResult,
							maxResults)
							).setEntity("value", propertyValue);
					return query.list();
				}};
		} else {
			callback = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					Criteria criteria = session.createCriteria(entityConfig.getRepresentedInterface()).setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
					addPropertyRestriction(indexConfig, criteria, propertyName, propertyValue);
					criteria.setFirstResult(firstResult);
					criteria.setMaxResults(maxResults);
					criteria.addOrder(Order.asc(entityConfig.getIdPropertyName()));
					return criteria.list();
				}};
		}
		return queryInTransaction(callback, session);
	}
	
	private void addPropertyRestriction(EntityIndexConfig indexConfig, Criteria criteria, String propertyName, Object propertyValue) {
		if (ReflectionTools.isCollectionProperty(config.getRepresentedInterface(), propertyName))
			if (ReflectionTools.isComplexCollectionItemProperty(config.getRepresentedInterface(), propertyName)) {
				criteria.createAlias(propertyName, "x")
					.add( Restrictions.eq("x." + indexConfig.getInnerClassPropertyName(), propertyValue));
			}
			else
				throw new UnsupportedOperationException("This call should have used HQL, not Criteria");
		else
			criteria.add( Restrictions.eq(propertyName, propertyValue));
	}
	
	protected Session createSessionForIndex(EntityConfig entityConfig, EntityIndexConfig indexConfig, Object propertyValue) {
		if (indexConfig.getIndexType().equals(IndexType.Delegates))
			return factory.openSession(
					((EntityIndexConfigDelegator)indexConfig).getDelegateEntityConfig().getResourceName(),
					propertyValue);
		else if (indexConfig.getIndexType().equals(IndexType.Hive))
			return factory.openSession(
					entityConfig.getResourceName(), 
					indexConfig.getIndexName(), 
					propertyValue);
		else if (indexConfig.getIndexType().equals(IndexType.Data))
			return factory.openAllShardsSession();
		else if (indexConfig.getIndexType().equals(IndexType.Partition))
			return factory.openSession(propertyValue);
		throw new RuntimeException(String.format("Unknown IndexType: %s", indexConfig.getIndexType()));
	}
	
	public Collection<Object> findByPropertyRange(final String propertyName, final Object minValue, final Object maxValue) {
		// Use an AllShardsresolutionStrategy + Criteria
		final EntityConfig entityConfig = config;
		final EntityIndexConfig indexConfig = config.getEntityIndexConfig(propertyName);
		Session session = factory.openAllShardsSession();
		QueryCallback callback;
		if (isComplexCollection(propertyName)) {
			callback = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					Query query = session.createQuery(String.format("from %s as x where x.%s between (:minValue, :maxValue)",
							entityConfig.getRepresentedInterface().getSimpleName(),
							indexConfig.getIndexName())
							).setEntity("minValue", minValue).setEntity("maxValue", maxValue);
						return query.list();
				}};
			
		}
		else {
			callback = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					Criteria criteria = session.createCriteria(config.getRepresentedInterface()).setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
					addPropertyRangeRestriction(indexConfig, criteria, propertyName, minValue, maxValue);
					return criteria.list();
				}};
		}
		return queryInTransaction(callback, session);
	}
	
	public Integer getCountByRange(final String propertyName, final Object minValue, final Object maxValue) {
		// Use an AllShardsresolutionStrategy + Criteria
		final EntityIndexConfig indexConfig = config.getEntityIndexConfig(propertyName);
		Session session = factory.openAllShardsSession();
		QueryCallback query = new QueryCallback(){
			@SuppressWarnings("unchecked")
			public Collection<Object> execute(Session session) {
				Criteria criteria = session.createCriteria(config.getRepresentedInterface()).setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
				addPropertyRangeRestriction(indexConfig, criteria, propertyName, minValue, maxValue);
				criteria.setProjection( Projections.rowCount() );
				return criteria.list();
			}};
		return (Integer)Atom.getFirstOrThrow(queryInTransaction(query, session));
	}
	
	public Collection<Object> findByPropertyRange(final String propertyName, final Object minValue, final Object maxValue, final Integer firstResult, final Integer maxResults) {
		// Use an AllShardsresolutionStrategy + Criteria
		final EntityConfig entityConfig = config;
		final EntityIndexConfig indexConfig = config.getEntityIndexConfig(propertyName);
		Session session = factory.openAllShardsSession();
		QueryCallback callback;
		if (isComplexCollection(propertyName)) {
			callback = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					Query query = session.createQuery(String.format("from %s as x where %s between (:minValue, :maxValue) order by x.%s asc limit %s, %s",
							entityConfig.getRepresentedInterface().getSimpleName(),
							indexConfig.getIndexName(),
							entityConfig.getIdPropertyName(),
							firstResult,
							maxResults)
							).setEntity("minValue", minValue).setEntity("maxValue", maxValue);
						return query.list();
				}};
		}
		else {
			callback = new QueryCallback(){
				@SuppressWarnings("unchecked")
				public Collection<Object> execute(Session session) {
					Criteria criteria = session.createCriteria(config.getRepresentedInterface()).setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
					addPropertyRangeRestriction(indexConfig, criteria, propertyName, minValue, maxValue);
					criteria.add( Restrictions.between(propertyName, minValue, maxValue));
					criteria.setFirstResult(firstResult);
					criteria.setMaxResults(maxResults);
					criteria.addOrder(Order.asc(propertyName));
					return criteria.list();
				}};
		}
		return queryInTransaction(callback, session);
	}
	
	private void addPropertyRangeRestriction(EntityIndexConfig indexConfig, Criteria criteria, String propertyName, Object minValue, Object maxValue) {
		if (ReflectionTools.isCollectionProperty(config.getRepresentedInterface(), propertyName))
			if (ReflectionTools.isComplexCollectionItemProperty(config.getRepresentedInterface(), propertyName)) {
				criteria.createAlias(propertyName, "x")
					.add(  Restrictions.between("x." + propertyName, minValue, maxValue));
			}
			else
				throw new UnsupportedOperationException("This isn't working yet");
		else
			criteria.add( Restrictions.between(propertyName, minValue, maxValue));
	}

	public Object save(final Object entity) {
		// Compensates for Hibernate's inability to delete items orphaned by updates
		//deleteOrphanedCollectionItems(Collections.singletonList(entity));
		SessionCallback callback = new SessionCallback(){
			public void execute(Session session) {
				session.saveOrUpdate(getRespresentedClass().getName(),entity);
			}};
		doInTransaction(callback, getSession());
		return entity;
	}
	
	public Object save(final Object entity, Session session) {
		// Compensates for Hibernate's inability to delete items orphaned by updates
		SessionCallback callback = new SessionCallback(){
			public void execute(Session session) {
				deleteOrphanedCollectionItems(Collections.singletonList(entity), session);
				session.saveOrUpdate(getRespresentedClass().getName(),entity);
			}};
		doInTransaction(callback, session);
		return entity;
	}

	public Collection<Object> saveAll(final Collection<Object> collection) {
		validateNonNull(collection);
		// Compensates for Hibernate's inability to delete items orphaned by updates
		//deleteOrphanedCollectionItems(collection);
		SessionCallback callback = new SessionCallback(){
			public void execute(Session session) {
				for(Object entity : collection) {
					session.saveOrUpdate(getRespresentedClass().getName(), entity);
				}
			}};
		doInTransaction(callback, getSession());
		return collection;
	}

	private void validateNonNull(final Collection<Object> collection) {
		if (Filter.isMatch(new Filter.NullPredicate<Object>(), collection)) {
			String ids = Amass.joinByToString(new Joiner.ConcatStrings<String>(", "), 
				Transform.map(new Unary<Object, String>() {
					public String f(Object item) {
						return item != null ? config.getId(item).toString() : "null"; }}, collection));
			throw new HiveRuntimeException(String.format("Encountered null items in collection: %s", ids));
		}
	}
	
	@SuppressWarnings("unchecked")
	private void deleteOrphanedCollectionItems(final Collection entities, final Session session) {
		
		final Map<Object, Object> entityToLoadedEntity = Transform.toMap(
				new IdentityFunction<Object>(),
				new Unary<Object,Object>() {
					public Object f(Object entity) {
						return get(config.getId(entity), session);
					}
				},
				entities,
				new Predicate<Map.Entry<Object,Object>>() {
					 public boolean f(Entry<Object, Object> entry) {
						return entry.getValue() != null;
				}});
		
		Collection<Object> existingKeys = Transform.map(new Unary<Object, Object>() {
			public Object f(Object entity) {
				return config.getId(entity);
			}
		},
		entityToLoadedEntity.keySet());
		for (Object entity : entities ) {
			if (!existingKeys.contains(config.getId(entity)))
				continue;
			Object loadedEntity = entityToLoadedEntity.get(entity);
			for (EntityIndexConfig entityIndexConfig : Filter.grep(new Predicate<EntityIndexConfig>() {
				public boolean f(EntityIndexConfig entityIndexConfig) {
					return ReflectionTools.isComplexCollectionItemProperty(config.getRepresentedInterface(), entityIndexConfig.getPropertyName());						
				}}, config.getEntityIndexConfigs())) {
				Collection<Object> newIds = entityIndexConfig.getIndexValues(entity);
				String indexItemIdProperty = entityIndexConfig.getInnerClassPropertyName();
				for (Object item : (Collection<Object>)ReflectionTools.invokeGetter(loadedEntity, entityIndexConfig.getPropertyName())) {
					Object itemId = ReflectionTools.invokeGetter(item, indexItemIdProperty);
					if (!newIds.contains(itemId)) {
						session.delete(item);
					}
				}
			}
		}
	}
	
	protected Session getSession() {
		return factory.openSession(factory.getDefaultInterceptor());
	}

	@SuppressWarnings("unchecked")
	public Class<Object> getRespresentedClass() {
		return (Class<Object>) EntityResolver.getPersistedImplementation(clazz);
	}
	
	public Interceptor getInterceptor() {return this.defaultInterceptor;}
	
	public void setInterceptor(Interceptor i) {this.defaultInterceptor = i;}
	
	public static void doInTransaction(SessionCallback callback, Session session) {
		Transaction tx = null;
		try {
			tx = session.beginTransaction();
			callback.execute(session);
			tx.commit();
		} catch (JDBCConnectionException ce) {
			throw new RuntimeException(ce);
		} catch( RuntimeException e ) {
			if(tx != null)
				try {
					tx.rollback();
				}
				catch (Exception ex){
					throw e;
				}
			throw e;
		} finally {
			session.close();
		}
	}
	
	public static Collection<Object> queryInTransaction(QueryCallback callback, Session session) {
		Collection<Object> results = Lists.newArrayList();
		try {
			Transaction tx = session.beginTransaction();
			results = callback.execute(session);
			tx.commit();
		} catch (JDBCConnectionException ce) {
			throw new RuntimeException(ce);
		} finally {
			session.close();
		}
		return results;
	}
}
