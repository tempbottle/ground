/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.berkeley.ground.api.models.cassandra;

import edu.berkeley.ground.api.models.NodeVersion;
import edu.berkeley.ground.api.models.NodeVersionFactory;
import edu.berkeley.ground.api.models.RichVersion;
import edu.berkeley.ground.api.models.Tag;
import edu.berkeley.ground.api.versions.GroundType;
import edu.berkeley.ground.db.CassandraClient;
import edu.berkeley.ground.db.CassandraClient.CassandraConnection;
import edu.berkeley.ground.db.DBClient;
import edu.berkeley.ground.db.DbDataContainer;
import edu.berkeley.ground.db.QueryResults;
import edu.berkeley.ground.exceptions.EmptyResultException;
import edu.berkeley.ground.exceptions.GroundException;
import edu.berkeley.ground.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class CassandraNodeVersionFactory extends NodeVersionFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraNodeVersionFactory.class);
    private CassandraClient dbClient;

    private CassandraNodeFactory nodeFactory;
    private CassandraRichVersionFactory richVersionFactory;

    public CassandraNodeVersionFactory(CassandraNodeFactory nodeFactory, CassandraRichVersionFactory richVersionFactory, CassandraClient dbClient) {
        this.dbClient = dbClient;
        this.nodeFactory = nodeFactory;
        this.richVersionFactory = richVersionFactory;
    }


    public NodeVersion create(Map<String, Tag> tags,
                              String structureVersionId,
                              String reference,
                              Map<String, String> referenceParameters,
                              String nodeId,
                              List<String> parentIds) throws GroundException {

        CassandraConnection connection = this.dbClient.getConnection();

        try {
            String id = IdGenerator.generateId(nodeId);

            // add the id of the version to the tag
            tags = tags.values().stream().collect(Collectors.toMap(Tag::getKey, tag -> new Tag(id, tag.getKey(), tag.getValue(), tag.getValueType())));

            this.richVersionFactory.insertIntoDatabase(connection, id, tags, structureVersionId, reference, referenceParameters);

            List<DbDataContainer> insertions = new ArrayList<>();
            insertions.add(new DbDataContainer("id", GroundType.STRING, id));
            insertions.add(new DbDataContainer("node_id", GroundType.STRING, nodeId));

            connection.insert("NodeVersions", insertions);

            this.nodeFactory.update(connection, nodeId, id, parentIds);

            connection.commit();
            LOGGER.info("Created node version " + id + " in node " + nodeId + ".");

            return NodeVersionFactory.construct(id, tags, structureVersionId, reference, referenceParameters, nodeId);
        } catch (GroundException e) {
            connection.abort();

            throw e;
        }
    }

    public NodeVersion retrieveFromDatabase(String id) throws GroundException {
        CassandraConnection connection = this.dbClient.getConnection();

        try {
            RichVersion version = this.richVersionFactory.retrieveFromDatabase(connection, id);

            List<DbDataContainer> predicates = new ArrayList<>();
            predicates.add(new DbDataContainer("id", GroundType.STRING, id));

            QueryResults resultSet;
            try {
                resultSet = connection.equalitySelect("NodeVersions", DBClient.SELECT_STAR, predicates);
            } catch (EmptyResultException eer) {
                throw new GroundException("No NodeVersion found with id " + id + ".");
            }

            if (!resultSet.next()) {
                throw new GroundException("No NodeVersion found with id " + id + ".");
            }

            String nodeId = resultSet.getString(1);

            connection.commit();
            LOGGER.info("Retrieved node version " + id + " in node " + nodeId + ".");

            return NodeVersionFactory.construct(id, version.getTags(), version.getStructureVersionId(), version.getReference(), version.getParameters(), nodeId);
        } catch (GroundException e) {
            connection.abort();

            throw e;
        }
    }

    public List<String> getTransitiveClosure(String nodeVersionId) throws GroundException {
        CassandraConnection connection = this.dbClient.getConnection();
        List<String> result = connection.transitiveClosure(nodeVersionId);

        connection.commit();
        return result;
    }

    public List<String> getAdjacentNodes(String nodeVersionId, String edgeNameRegex) throws GroundException {
        CassandraConnection connection = this.dbClient.getConnection();
        List<String> result = connection.adjacentNodes(nodeVersionId, edgeNameRegex);

        connection.commit();
        return result;
    }
}
