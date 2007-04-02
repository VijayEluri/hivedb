package org.hivedb.util.scenarioBuilder;

import java.util.Collection;

import org.hivedb.Hive;
import org.hivedb.meta.Node;

/**
 *  An interface to allow generating a hive schema and filling it with test data.
 *  The interface allows listing of various classes to represent a PartitionDimension and/or Resource
 *  PartitionDimension classes must implement PrimaryIndexIdentifiable to allow the HiveScenario to mode a PartitionDimension after it
 *  Resource classes must implement ResourceIdentifiable which in turn references a collection of SecondaryIndexIdentifiable instances.
 *  A class may be both a PrimaryIndexIdentifiable and ResourceIdentifiable if it is the basis of the PartitionDimension but also
 *  has SecondaryIndexes, such as an index for its getName() values.
 * @author andylikuski
 *
 */
public interface HiveScenarioConfig {
	int getInstanceCountPerPrimaryIndex();
	int getInstanceCountPerSecondaryIndex();
	
	Collection<PrimaryIndexIdentifiable> getPrimaryInstanceIdentifiables();
	
	Hive getHive();
	// The uris of the databases used for the index servers. This may be a single URI matching the Hive URI.
	Collection<String> getIndexUris(Hive hive);
	// The nodes of representing the data storage databases.
	Collection<Node> getDataNodes(Hive hive);
}
