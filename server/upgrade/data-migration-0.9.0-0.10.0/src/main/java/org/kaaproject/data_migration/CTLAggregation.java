/*
 * Copyright 2014-2016 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kaaproject.data_migration;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.kaaproject.data_migration.model.Ctl;
import org.kaaproject.data_migration.model.CtlMetaInfo;
import org.kaaproject.data_migration.model.Schema;
import org.kaaproject.data_migration.utils.datadefinition.DataDefinition;
import org.kaaproject.kaa.server.common.core.algorithms.generation.ConfigurationGenerationException;
import org.kaaproject.kaa.server.common.core.algorithms.generation.DefaultRecordGenerationAlgorithm;
import org.kaaproject.kaa.server.common.core.algorithms.generation.DefaultRecordGenerationAlgorithmImpl;
import org.kaaproject.kaa.server.common.core.configuration.RawData;
import org.kaaproject.kaa.server.common.core.configuration.RawDataFactory;
import org.kaaproject.kaa.server.common.core.schema.RawSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Arrays.asList;

public class CTLAggregation {
    private Connection connection;
    private QueryRunner runner;
    private DataDefinition dd;

    private static final Logger LOG = LoggerFactory.getLogger(CTLAggregation.class);
    private Map<Ctl, List<Schema>> confSchemasToCTL;
    private Set<Ctl> ctls;


    public CTLAggregation(Connection connection) {
        this.connection = connection;
        runner = new QueryRunner();
        dd = new DataDefinition(connection);
    }


    public Map<Ctl, List<Schema>> aggregate(List<Schema> schemas) throws SQLException, ConfigurationGenerationException, IOException {
        confSchemasToCTL = new HashMap<>();
        ctls = new HashSet<>();
        Long currentCTLMetaId = runner.query(connection, "select max(id) as max_id from ctl_metainfo", rs -> rs.next() ? rs.getLong("max_id") : null);
        Long currentCtlId = runner.query(connection, "select max(id) as max_id from ctl", rs -> rs.next() ? rs.getLong("max_id") : null);

        // CTL creation
        for (Schema schema : schemas) {
            currentCTLMetaId++;
            currentCtlId++;
            org.apache.avro.Schema schemaBody = new org.apache.avro.Schema.Parser().parse(schema.getSchems());
            String fqn = schemaBody.getFullName();
            RawSchema rawSchema = new RawSchema(schemaBody.toString());
            DefaultRecordGenerationAlgorithm<RawData> algotithm = new DefaultRecordGenerationAlgorithmImpl<>(rawSchema, new RawDataFactory());
            String defaultRecord = algotithm.getRootData().getRawData();
            Long tenantId = runner.query(connection, "select tenant_id from application where id = " + schema.getAppId(), rs -> rs.next() ? rs.getLong("tenant_id") : null);
            Ctl ctl = new Ctl(currentCtlId, new CtlMetaInfo(currentCTLMetaId, fqn, schema.getAppId(), tenantId), defaultRecord);

            if (ctls.isEmpty()) {
                confSchemasToCTL.put(ctl, new ArrayList<>(asList(schema)));
                ctls.add(ctl);
            } else {
                Ctl ctlToCompare = sameFqn(ctls, ctl);

                if (ctlToCompare != null) {

                    if (bothAppIdNull(ctlToCompare, ctl)) {

                        if (sameTenant(ctlToCompare, ctl)) {
                            aggregateSchemas(ctlToCompare, ctl, schema);
                        } else {
                            putToMapSchema(ctlToCompare, ctl, schema, "tenant");
                        }

                    } else {

                        if (sameAppId(ctlToCompare, ctl)) {
                            aggregateSchemas(ctlToCompare, ctl, schema);
                        } else {
                            putToMapSchema(ctlToCompare, ctl, schema, "application");
                        }

                    }

                } else {
                    ctlToCompare = sameBody(ctls, ctl);
                    if (ctlToCompare != null) {
                        LOG.warn("Schemas {} and {} have different fqn but same body {}", ctl.getMetaInfo().getFqn(), ctlToCompare.getMetaInfo().getFqn(), ctl.getDefaultRecord());
                    }
                    confSchemasToCTL.put(ctl, new ArrayList<>(asList(schema)));
                    ctls.add(ctl);
                }
            }

        }

        for (Ctl ctl : ctls) {
            CtlMetaInfo mi = ctl.getMetaInfo();
            Schema s = confSchemasToCTL.get(ctl).get(0);
            runner.insert(connection, "insert into ctl_metainfo values(?, ?, ?, ?)", new ScalarHandler<Long>(), mi.getId(), mi.getFqn(), mi.getAppId(), mi.getTenatnId());
            runner.insert(connection, "insert into ctl values(?, ?, ?, ?, ?, ?, ?)", new ScalarHandler<Long>(), ctl.getId(), s.getSchems(), s.getCreatedTime(),
                    s.getCreatedUsername(), ctl.getDefaultRecord(), s.getVersion(), mi.getId());

        }

        return confSchemasToCTL;

    }


    private Ctl sameFqn(Set<Ctl> set, Ctl ctl) {
        for (Ctl ctl1 : set) {
            if (ctl1.getMetaInfo().getFqn().equals(ctl.getMetaInfo().getFqn())) {
                return ctl1;
            }
        }
        return null;
    }


    private Ctl sameBody(Set<Ctl> set, Ctl ctl) {
        for (Ctl ctl1 : set) {
            if (ctl1.getMetaInfo().getFqn().equals(ctl.getMetaInfo().getFqn())) {
                return ctl1;
            }
        }
        return null;
    }

    private boolean bothAppIdNull(Ctl c1, Ctl c2) {
        return c1.getMetaInfo().getAppId() == null && c2.getMetaInfo().getAppId() == null;
    }


    private boolean sameBody(Ctl c1, Ctl c2) {
        return c1.getDefaultRecord().equals(c2.getDefaultRecord());
    }

    private boolean sameAppId(Ctl c1, Ctl c2) {
        return c1.getMetaInfo().getAppId().equals(c2.getMetaInfo().getAppId());
    }


    private boolean sameTenant(Ctl c1, Ctl c2) {
        return c1.getMetaInfo().getTenatnId().equals(c2.getMetaInfo().getTenatnId());
    }


    ///TODO in future add promotion if needed
    private void putToMapSchema(Ctl c1, Ctl newCtl, Schema schema, String scope) {
        if (!sameBody(c1, newCtl)) {
            LOG.warn("Schemas in different %ss' scopes have different bodies {} and {} but the same fqn {}", scope, newCtl.getDefaultRecord(), c1.getDefaultRecord(), newCtl.getMetaInfo().getFqn());
        }
        confSchemasToCTL.put(newCtl, new ArrayList<>(asList(schema)));
        ctls.add(newCtl);
    }

    private void aggregateSchemas(Ctl c1, Ctl c2, Schema schema) {
        if (!sameBody(c1, c2)) {
            CtlMetaInfo mi = c1.getMetaInfo();
            String message = format("Unable to do migrate due to schemas with same fqn[%s] and scope[appId=%d, tenant=%d] but different bodies", mi.getFqn(), mi.getAppId(), mi.getTenatnId());
            throw new MigrationException(message);
        }
        List<Schema> sc = confSchemasToCTL.get(c1);
        sc.add(schema);
    }
}
