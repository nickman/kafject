/**
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 */
package com.heliosapm.streams.metrics.router.nodes;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedOperationParameter;
import org.springframework.jmx.export.annotation.ManagedOperationParameters;

import com.heliosapm.streams.metrics.StreamedMetric;
import com.heliosapm.streams.metrics.StreamedMetricValue;
import com.heliosapm.streams.metrics.router.StreamHubKafkaClientSupplier;
import com.heliosapm.streams.metrics.router.util.TimeWindowSummary;
import com.heliosapm.streams.serialization.HeliosSerdes;
import com.heliosapm.utils.tuples.NVP;

/**
 * <p>Title: StreamedMetricMeterNode</p>
 * <p>Description: Provides metering for incoming metrics where the total number of
 * {@link StreamedMetric}s ingested will be accumulated in fixed time windows then forwarded
 * as a new metric with the number of incidents as the value. There are some variables: <ol>
 * 	<li><b>{@link #windowSize}</b>: The size of the window period to accumulate within in ms.</li>
 *  <li><b>{@link #ignoreValues}</b>: </li>
 *  <li><b>{@link #ignoreDoubles}</b>: </li>
 *  <li><b>{@link #reportInSeconds}</b>: If true, the final count will be adjusted to report events per second.</li>
 * 
 * </ol></p> 
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.metrics.router.nodes.StreamedMetricMeterNode</code></p>
 */

public class StreamedMetricMeterNode extends AbstractMetricStreamNode  {
	/** The accumulation window size in ms. Defaults to 5000 */
	private long windowSize = 5000;
	/** Indicates if the actual value of a metric should be ignored and to focus only on the count of the metric id. Defaults to false. */
	private boolean ignoreValues = false;
	/** Indicates if metrics that have a value type of {@link Double} should be ignored. Defaults to false. */
	private boolean ignoreDoubles = false;
	/** Indicates if the reported value published upstream should be adjusted to per/Second (i.e. the {@link #windowSize} divided by 1000). Defaults to true. */
	private boolean reportInSeconds = true;
	/** The time window summary to report the final summary using the window start, end (default) or middle time */
	private TimeWindowSummary windowTimeSummary = TimeWindowSummary.END;
	/** The divisor to report tps (windowSize/1000) */
	private double tpsDivisor = 5D;
	/** The number of outbounds sent in the last punctuation */
	private final LongAdder lastOutbound = new LongAdder();
	
	private WindowAggregation<String, StreamedMetricMeterAggregator, StreamedMetric> wa = null;
	private Producer<String, StreamedMetric> producer = null;
	
	
	
	
	
	

	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.metrics.router.nodes.MetricStreamNode#configure(org.apache.kafka.streams.kstream.KStreamBuilder)
	 */
	@Override
	public void configure(final KStreamBuilder streamBuilder) {
		log.info("Source Topics: {}", Arrays.toString(sourceTopics));
		log.info("Sink Topic: [{}]", sinkTopic);
		// ========================= PROCESSOR STYLE =========================
		// persistentStores
//		final KeyValueFactory<String, long[]> factory = Stores.create(storeName).withStringKeys().withValues(HeliosSerdes.TIMEWINDOW_VALUE_SERDE);
//		final StateStoreSupplier ss = persistentStores ? factory.persistent().build() : factory.inMemory().build();
//		
//		streamBuilder
//			.addSource("MeterMetricProcessorSource", HeliosSerdes.STRING_SERDE.deserializer(), HeliosSerdes.STREAMED_METRIC_SERDE.deserializer(), sourceTopics)			
//			.addProcessor("MeterMetricProcessor", this, "MeterMetricProcessorSource")
//			.addStateStore(ss, "MeterMetricProcessor")
//			.addSink("MeterMetricProcessorSink", sinkTopic, HeliosSerdes.STRING_SERDE.serializer(), HeliosSerdes.STREAMED_METRIC_SERDE.serializer(), "MeterMetricProcessor");
		// ===================================================================
		wa = WindowAggregation.getInstance(TimeUnit.MILLISECONDS.toSeconds(windowSize), 0, true, StreamedMetricMeterAggregator.AGGREGATOR);
		streamBuilder
			.stream(HeliosSerdes.STRING_SERDE, HeliosSerdes.STREAMED_METRIC_SERDE, sourceTopics)
			.foreach((k,v) -> {
				wa.aggregate(k, v.forValue(1L));
				inboundCount.increment();
			});
		
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.metrics.router.nodes.AbstractMetricStreamNode#onStart(com.heliosapm.streams.metrics.router.StreamHubKafkaClientSupplier, org.apache.kafka.streams.KafkaStreams)
	 */
	@Override
	public void onStart(final StreamHubKafkaClientSupplier clientSupplier, final KafkaStreams kafkaStreams) {		
		super.onStart(clientSupplier, kafkaStreams);
		producer = clientSupplier.getProducer(HeliosSerdes.STRING_SERDE, HeliosSerdes.STREAMED_METRIC_SERDE);
		wa.addAction((stream, keys) -> stream.forEach(kv -> { 
			outboundCount.increment();
			producer.send(new ProducerRecord<String, StreamedMetric>(sinkTopic, kv.key, kv.value));
			producer.flush();
		}));
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.metrics.router.nodes.AbstractMetricStreamNode#close()
	 */
	@Override
	public void close() {		
		if(wa!=null) {
			try { wa.close(); } catch (Exception x) {/* No Op */}
		}
		if(producer!=null) {
			try { producer.close(); } catch (Exception x) {/* No Op */}
		}
		
		super.close();		
	}
	
	/**
	 * Calculates the TPS rate
	 * @param count the number of events received during the window period
	 * @return the number of events per second
	 */
	public double calcRate(final double count) {
		if(count==0D) return 0D;
		return count/tpsDivisor;
	}
	

	/**
	 * Returns the configured window size in ms. 
	 * @return the window size
	 */
	@ManagedAttribute(description="The configured window size in ms.")
	public long getWindowSize() {
		return windowSize;
	}
	

	/**
	 * Sets the window size in ms. Should be a multiple of 1000
	 * @param windowSize the window size to set
	 */
	public void setWindowSize(final long windowSize) {
		if(windowSize < 1000) throw new IllegalArgumentException("Invalid window size: [" + windowSize + "]");
		this.windowSize = windowSize;
		tpsDivisor = this.windowSize/1000D;
	}

	/**
	 * Indicates if metrics with values are counted as 1 event or the number of the value
	 * @return true if values are ignored, false otherwise
	 */
	@ManagedAttribute(description="Indicates if metrics with values are counted as 1 event or the number of the value")
	public boolean isIgnoreValues() {
		return ignoreValues;
	}

	/**
	 * Sets if metrics with values are counted as 1 event
	 * @param ignoreValues true to treat metrics with values as 1 event, false to use the value 
	 */
	public void setIgnoreValues(final boolean ignoreValues) {
		this.ignoreValues = ignoreValues;
	}

	/**
	 * Indicates if metrics with double values are counted as 1 event or the number of the value
	 * @return true if values are ignored, false otherwise
	 */
	@ManagedAttribute(description="Indicates if metrics with double values are counted as 1 event or the number of the value")
	public boolean isIgnoreDoubles() {
		return ignoreDoubles;
	}

	/**
	 * Sets if metrics with double values are counted as 1 event
	 * @param ignoreDoubles true to treat metrics with double values as 1 event, false to use the value 
	 */
	public void setIgnoreDoubles(final boolean ignoreDoubles) {
		this.ignoreDoubles = ignoreDoubles;
	}

	/**
	 * Indicates if final rates are reported in events/sec or the natural rate of the configured window
	 * @return true if final rates are reported in tps, false otherwise
	 */
	@ManagedAttribute(description="Indicates if final rates are reported in events/sec")
	public boolean isReportInSeconds() {
		return reportInSeconds;
	}

	/**
	 * Specifies if final rates are reported in events/sec or the natural rate of the configured window
	 * @param reportInSeconds true to report in tps, false otherwise
	 */
	public void setReportInSeconds(boolean reportInSeconds) {
		this.reportInSeconds = reportInSeconds;
	}

	/**
	 * Returns the time window summarization strategy
	 * @return the time window summarization strategy
	 */
	@ManagedAttribute(description="The time window summarization strategy")
	public String getWindowTimeSummary() {
		return windowTimeSummary.name();
	}

	/**
	 * Sets the time window summarization strategy
	 * @param windowSum The time window summarization strategy
	 */
	public void setWindowTimeSummary(final TimeWindowSummary windowSum) {
		this.windowTimeSummary = windowSum;
	}

	/**
	 * Returns the number of sunk events in the last punctuation
	 * @return the number of sunk events in the last punctuation
	 */
	@ManagedAttribute(description="The number of sunk events in the last punctuation")
	public long getLastOutbound() {
		return lastOutbound.longValue();
	}



}
