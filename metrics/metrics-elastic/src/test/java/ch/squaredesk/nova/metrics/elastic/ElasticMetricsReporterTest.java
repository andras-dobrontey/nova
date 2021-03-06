/*
 * Copyright (c) 2020 Squaredesk GmbH and Oliver Dotzauer.
 *
 * This program is distributed under the squaredesk open source license. See the LICENSE file
 * distributed with this work for additional information regarding copyright ownership. You may also
 * obtain a copy of the license at
 *
 *   https://squaredesk.ch/license/oss/LICENSE
 *
 */
package ch.squaredesk.nova.metrics.elastic;

import ch.squaredesk.nova.metrics.CompoundMetric;
import ch.squaredesk.nova.metrics.Metrics;
import ch.squaredesk.nova.metrics.MetricsDump;
import ch.squaredesk.nova.metrics.SerializableMetricsDump;
import ch.squaredesk.nova.tuples.Pair;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.observers.TestObserver;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.bytes.BytesReference;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

class ElasticMetricsReporterTest {
    private ElasticMetricsReporter sut;

    @BeforeEach
    void setup() {
        sut = new ElasticMetricsReporter("index","127.0.0.1:9300");
    }

    @Test
    void nothingHappensOnShutdownIfNotConnected() {
        assertDoesNotThrow(sut::shutdown);
    }

    @Test
    void transmittingThrowsIfNotStartedYet() {
        Metrics metrics = new Metrics();
        metrics.getCounter("test", "counter1");
        metrics.getMeter("test", "meter1");
        metrics.register(new MyMetric(), "test", "myMetric1");
        MetricsDump dump = metrics.dump();

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> sut.accept(dump)
                );
        assertThat(ex.getMessage(), is("not started yet"));
    }

    @Test
    void requestFromMetricsDumpIsCreatedAsExpected() throws Exception {
        Metrics metrics = new Metrics();
        metrics.getCounter("test","counter1");
        metrics.getMeter("test","meter1");
        metrics.register(new MyMetric(), "test","myMetric1");
        metrics.addAdditionalInfoForDumps("host", "myMachine");
        metrics.addAdditionalInfoForDumps("hostAddress", "myAddress");
        metrics.addAdditionalInfoForDumps("additionalKey", "additionalValue");
        MetricsDump dump = metrics.dump();

        TestObserver<BulkRequest> bulkRequestObserver = sut.requestFor(dump).test();
        bulkRequestObserver.assertComplete();
        bulkRequestObserver.assertValueCount(1);
        BulkRequest bulkRequest = bulkRequestObserver.values().get(0);

        List<DocWriteRequest<?>> requests = bulkRequest.requests();
        assertThat(requests.size(), is(3));
        for (DocWriteRequest request: requests) {
            assertTrue(request instanceof IndexRequest);
            IndexRequest ir = (IndexRequest)request;
            Map<String,Object> sourceAsMap = getMapFrom(ir.source());
            assertThat(sourceAsMap.get("@timestamp"), is(notNullValue()));
            dump.additionalInfo.forEach(entry -> {
                assertThat(sourceAsMap.get(entry._1), is(entry._2));
            });
            assertThat(sourceAsMap.get("name"),Matchers.oneOf("test.counter1","test.meter1","test.myMetric1"));
        }
    }

    @Test
    void requestFromSerializableMetricsDumpIsCreatedAsExpected() throws Exception {
        Metrics metrics = new Metrics();
        metrics.getCounter("test","counter1");
        metrics.getMeter("test","meter1");
        metrics.register(new MyMetric(), "test","myMetric1");
        metrics.addAdditionalInfoForDumps("host", "myMachine2");
        metrics.addAdditionalInfoForDumps("hostAddress", "myAddress2");
        metrics.addAdditionalInfoForDumps("additionalKey", "additionalValue2");
        MetricsDump dump = metrics.dump();
        SerializableMetricsDump serializableDump = SerializableMetricsDump.createFor(dump);

        TestObserver<BulkRequest> bulkRequestObserver = sut.requestFor(serializableDump).test();
        bulkRequestObserver.assertComplete();
        bulkRequestObserver.assertValueCount(1);
        BulkRequest bulkRequest = bulkRequestObserver.values().get(0);

        List<DocWriteRequest<?>> requests = bulkRequest.requests();
        assertThat(requests.size(), is(3));
        for (DocWriteRequest request: requests) {
            assertTrue(request instanceof IndexRequest);
            IndexRequest ir = (IndexRequest)request;
            Map<String,Object> sourceAsMap = getMapFrom(ir.source());
            assertThat(sourceAsMap.get("@timestamp"), is(notNullValue()));
            dump.additionalInfo.forEach(entry -> {
                assertThat(sourceAsMap.get(entry._1), is(entry._2));
            });
            assertThat(sourceAsMap.get("name"), Matchers.oneOf("test.counter1", "test.meter1", "test.myMetric1"));
        }
    }

    @Test
    void requestFromMapDumpIsCreatedAsExpected() throws Exception {
        Map<String, Object> dumpAsMap = new HashMap<>();
        Arrays.asList(new Pair<>("counter", "counter1"),
                new Pair<>("meter", "meter1"),
                new Pair<>("MyMetric", "myMetric1"))
                .forEach(pair -> {
                    Map<String, Object> metricMap = new HashMap<>();
                    metricMap.put("type", pair._1);
                    metricMap.put("someAttribute", "someVal");
                    dumpAsMap.put("test." + pair._2, metricMap);
                });
        dumpAsMap.put("hostName", "someVal");

        TestObserver<BulkRequest> bulkRequestObserver = sut.requestFor(dumpAsMap).test();
        bulkRequestObserver.assertComplete();
        bulkRequestObserver.assertValueCount(1);
        BulkRequest bulkRequest = bulkRequestObserver.values().get(0);

        List<DocWriteRequest<?>> requests = bulkRequest.requests();
        assertThat(requests.size(), is(3));
        for (DocWriteRequest request: requests) {
            assertTrue(request instanceof IndexRequest);
            IndexRequest ir = (IndexRequest) request;
            Map<String, Object> sourceAsMap = getMapFrom(ir.source());
            assertNotNull(sourceAsMap.get("@timestamp"));
            assertNotNull(sourceAsMap.get("someAttribute"));
            assertThat(sourceAsMap.get("name"), Matchers.oneOf("test.counter1", "test.meter1", "test.myMetric1"));
            assertThat(sourceAsMap.get("type"), Matchers.oneOf("counter", "meter", "MyMetric"));
        }
    }

    private Map<String,Object> getMapFrom (BytesReference source) throws Exception {
        return new ObjectMapper().readValue(source.utf8ToString(), Map.class);
    }

    private class MyMetric implements CompoundMetric {
        @Override
        public Map<String,Object> getValues() {
            return new HashMap<>();
        }
    }

}