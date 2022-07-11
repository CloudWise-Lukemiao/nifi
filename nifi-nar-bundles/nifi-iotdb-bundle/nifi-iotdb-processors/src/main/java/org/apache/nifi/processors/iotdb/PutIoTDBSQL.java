/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.iotdb;

import static java.lang.System.currentTimeMillis;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;

@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@EventDriven
@SupportsBatching
@Tags({"IoTDB", "measurement","insert", "write", "put", "timeseries"})
@CapabilityDescription("Processor to write the content of a FlowFile in 'line protocol'.  Please check details of the 'line protocol' in IoTDB documentation (https://www.IoTDB.com/). "
        + "  The flow file can contain single measurement point or multiple measurement points separated by line seperator.  The timestamp (last field) should be in nano-seconds resolution.")
@WritesAttributes({
    @WritesAttribute(attribute = AbstractIoTDBProcessor.IOTDB_ERROR_MESSAGE, description = "IoTDB error message"),
    })
public class PutIoTDBSQL extends AbstractIoTDBProcessor {

    static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
            .description("Successful FlowFiles that are saved to IoTDB are routed to this relationship").build();

    static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
            .description("FlowFiles were not saved to IoTDB are routed to this relationship").build();

    static final Relationship REL_RETRY = new Relationship.Builder().name("retry")
            .description("FlowFiles were not saved to IoTDB due to retryable exception are routed to this relationship").build();

    static final Relationship REL_MAX_SIZE_EXCEEDED = new Relationship.Builder().name("failure-max-size")
            .description("FlowFiles exceeding max records size are routed to this relationship").build();

    private static final Set<Relationship> relationships;
    private static final List<PropertyDescriptor> propertyDescriptors;

    static {
        relationships = Set.of(REL_SUCCESS, REL_FAILURE, REL_RETRY, REL_MAX_SIZE_EXCEEDED);

        propertyDescriptors = List
            .of(IOTDB_JDBC_URL, USERNAME, PASSWORD, CHARSET, MAX_RECORDS_SIZE);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        super.onScheduled(context);
        maxRecordsSize = context.getProperty(MAX_RECORDS_SIZE).evaluateAttributeExpressions().asDataSize(DataUnit.B).longValue();
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        if ( flowFile.getSize() == 0) {
            getLogger().error("Empty measurements");
            flowFile = session.putAttribute(flowFile, IOTDB_ERROR_MESSAGE, "Empty measurement size " + flowFile.getSize());
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        if ( flowFile.getSize() > maxRecordsSize) {
            getLogger().error("Message size of records exceeded {} max allowed is {}", flowFile.getSize(),
                maxRecordsSize);
            flowFile = session.putAttribute(flowFile, IOTDB_ERROR_MESSAGE, "Max records size exceeded " + flowFile.getSize());
            session.transfer(flowFile, REL_MAX_SIZE_EXCEEDED);
            return;
        }

        Charset charset = Charset.forName(context.getProperty(CHARSET).evaluateAttributeExpressions(flowFile).getValue());


        try {
            long startTimeMillis = currentTimeMillis();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            session.exportTo(flowFile, baos);
            String records = baos.toString(charset);

            insertIoTDB(context, records);

            final long endTimeMillis = currentTimeMillis();
            getLogger().debug("Records {} inserted", records);

            session.transfer(flowFile, REL_SUCCESS);
            session.getProvenanceReporter().send(flowFile,
                "iotdb://" + context.getProperty(IOTDB_DB_URL).evaluateAttributeExpressions()
                    .getValue() + "/",
                (endTimeMillis - startTimeMillis));
        } catch (Exception exception) {
            getLogger().error("Failed to insert into IoTDB due to {}",
                new Object[]{exception.getLocalizedMessage()}, exception);
            flowFile = session.putAttribute(flowFile, IOTDB_ERROR_MESSAGE, String.valueOf(exception.getMessage()));
            session.transfer(flowFile, REL_FAILURE);
            context.yield();
        }
    }

    protected  void insertIoTDB(ProcessContext context, String sql){
       Connection connection= getIoTDBconnection(context);
        try {
            Statement statement= connection.createStatement();
            statement.setFetchSize(10000);
            statement.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
            getLogger().error("Failed to insert into IoTDB due to {}"+"----"+e.getMessage()+"--",e);
        }
    }

    @OnStopped
    public void close() {
        super.close();
    }
}