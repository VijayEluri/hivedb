package org.hivedb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.hivedb.meta.AccessType;
import org.hivedb.meta.KeySemaphore;
import org.hivedb.meta.Node;
import org.hivedb.meta.PartitionDimension;
import org.hivedb.meta.Resource;
import org.hivedb.meta.SecondaryIndex;
import org.hivedb.meta.directory.Directory;
import org.hivedb.meta.persistence.DataSourceProvider;
import org.hivedb.util.Preconditions;
import org.hivedb.util.functional.Filter;
import org.hivedb.util.functional.Unary;
import org.springframework.jdbc.core.simple.SimpleJdbcDaoSupport;

/**
 * @author Britt Crawford (bcrawford@cafepress.com)
 *
 */
public class JdbcDaoSupportCacheImpl implements JdbcDaoSupportCache{
	private Map<Integer, SimpleJdbcDaoSupport> jdbcDaoSupports;
	private Directory directory;
	private DataSourceProvider dataSourceProvider;
	
	public JdbcDaoSupportCacheImpl(Directory directory,  DataSourceProvider dataSourceProvider) {
		this.directory = directory;
		this.dataSourceProvider = dataSourceProvider;
		this.jdbcDaoSupports = loadDataSourceMap(directory.getPartitionDimension());
	}

	private Map<Integer, SimpleJdbcDaoSupport> loadDataSourceMap(PartitionDimension dimension) {
		Map<Integer, SimpleJdbcDaoSupport> jdbcDaoSupports = new ConcurrentHashMap<Integer, SimpleJdbcDaoSupport>();
		for(Node node :  dimension.getNodes()) 
			jdbcDaoSupports.put(node.getId(), makeDaoSupport(node));
		return jdbcDaoSupports;
	}

	private SimpleJdbcDaoSupport makeDaoSupport(Node node) {
		return new DataNodeJdbcDaoSupport(dataSourceProvider.getDataSource(node.getUri()));
	}
	
	public SimpleJdbcDaoSupport addDataSource(Node node) {
		jdbcDaoSupports.put(node.getId(), makeDaoSupport(node));
		return jdbcDaoSupports.get(node.getId());
	}
	
	public SimpleJdbcDaoSupport removeDataSource(Node node) {
		return jdbcDaoSupports.remove(node.getId());
	}
	
	private SimpleJdbcDaoSupport get(KeySemaphore semaphore, AccessType intention) throws HiveReadOnlyException { 
		Node node = null;
		node = directory.getPartitionDimension().getNode(semaphore.getId());
		
		if(intention == AccessType.ReadWrite)
			Preconditions.isWritable(node, semaphore);
		
		if( jdbcDaoSupports.containsKey(semaphore.getId()))
			return jdbcDaoSupports.get(semaphore.getId());
		
		throw new HiveKeyNotFoundException("Could not find dataSource for ", semaphore);
	}

	/**
	 * Get a SimpleJdbcDaoSupport by primary partition key.
	 * @param partitionDimension The partition dimension
	 * @param primaryIndexKey The partition key
	 * @param intention The permissions with which you wish to acquire the conenction
	 * @return
	 * @throws HiveReadOnlyException
	 */
	public Collection<SimpleJdbcDaoSupport> get(Object primaryIndexKey, final AccessType intention) throws HiveReadOnlyException {
		Collection<KeySemaphore> semaphores = directory.getNodeSemamphoresOfPrimaryIndexKey(primaryIndexKey);
		Collection<SimpleJdbcDaoSupport> supports = new ArrayList<SimpleJdbcDaoSupport>();
		for(KeySemaphore semaphore : semaphores)
			supports.add(get(semaphore, intention));
		return supports;
	}

	/**
	 * Get a SimpleJdbcDaoSupport by secondary index key.
	 * @param secondaryIndex The secondary index to search on
	 * @param secondaryIndexKey The secondary key
	 * @param intention The permissions with which you wish to acquire the conenction
	 * @return
	 * @throws HiveReadOnlyException
	 */
	public Collection<SimpleJdbcDaoSupport> get(SecondaryIndex secondaryIndex, Object secondaryIndexKey, final AccessType intention) throws HiveReadOnlyException {
		if(AccessType.ReadWrite == intention) 
			if(Filter.getUnique(directory.getPrimaryIndexKeysOfSecondaryIndexKey(secondaryIndex, secondaryIndexKey)).size() > 1)
				throw new UnsupportedOperationException("Writes for non-unique secondary indexes must be performed using the primary index key.");
		
		Collection<KeySemaphore> keySemaphores = directory.getKeySemaphoresOfSecondaryIndexKey(secondaryIndex, secondaryIndexKey);
		keySemaphores = Filter.getUnique(keySemaphores, new Unary<KeySemaphore, Integer>(){
			public Integer f(KeySemaphore item) {
				return item.getId();
		}});
		
		Collection<SimpleJdbcDaoSupport> supports = new ArrayList<SimpleJdbcDaoSupport>();
		for(KeySemaphore semaphore : keySemaphores)
			supports.add(get(semaphore, intention));
		return supports;
	}
	
	private static class DataNodeJdbcDaoSupport extends SimpleJdbcDaoSupport
	{
		public DataNodeJdbcDaoSupport(DataSource dataSource)
		{
			this.setDataSource(dataSource);
		}
	}

	/**
	 * IMPORTANT -- This bypasses the locking mechanism.  You should only use this
	 * to install schema before data nodes have been populated.
	 */
	public SimpleJdbcDaoSupport getUnsafe(Node node) {
		try {
			KeySemaphore semaphore = new KeySemaphore(node.getId(), node.isReadOnly());
			return get(semaphore, AccessType.ReadWrite);
		} catch (HiveException e) {
			throw new RuntimeException(e);
		}
	}
	
	public SimpleJdbcDaoSupport getUnsafe(String nodeName) {
		try {
			Node node = directory.getPartitionDimension().getNode(nodeName);
			KeySemaphore semaphore = new KeySemaphore(node.getId(), node.isReadOnly());
			return get(semaphore, AccessType.ReadWrite);
		} catch (HiveException e) {
			throw new RuntimeException(e);
		}
	}

	public Collection<SimpleJdbcDaoSupport> get(Resource resource, Object resourceId, AccessType intention) throws HiveReadOnlyException {
		Collection<KeySemaphore> semaphores = directory.getKeySemaphoresOfResourceId(resource, resourceId);
		Collection<SimpleJdbcDaoSupport> supports = new ArrayList<SimpleJdbcDaoSupport>();
		for(KeySemaphore semaphore : semaphores)
			supports.add(get(semaphore, intention));
		return supports;
	}

	public Collection<SimpleJdbcDaoSupport> getAllUnsafe() {
		Collection<SimpleJdbcDaoSupport> daos = new ArrayList<SimpleJdbcDaoSupport>();
		for(Node node : directory.getPartitionDimension().getNodes())
			daos.add(jdbcDaoSupports.get(node.getId()));
		return daos;
	}
}
