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
package com.heliosapm.streams.metrichub.results;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.heliosapm.streams.json.JSONOps;
import com.heliosapm.streams.tracing.TagKeySorter.TagMap;
import com.heliosapm.utils.url.URLHelper;

/**
 * <p>Title: QueryResult</p>
 * <p>Description: Represents the query results for one distinct metric in an <b>/api/query</b> query to OpenTSDB</p> 
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.metrichub.results.QueryResult</code></p>
 */
@JsonDeserialize(using=QueryResultDeserializer.class)

public class QueryResult {
	/** The metric name */
	protected final String metricName;
	/** The metric tags */
	protected final Map<String, String> tags;
	/** Aggregated tags */
	protected final String[] aggregatedTags;
	/** The data points */
	protected final TreeSet<long[]> dps = new TreeSet<long[]>(DPS_COMPARATOR);
	
	
	
	/** Type reference for a tag key sorted map */
	public static final TypeReference<TagMap> TREE_MAP_TYPE_REF = new TypeReference<TagMap>(){};
	/** Type reference for a QueryResult array */
	public static final TypeReference<QueryResult[]> QR_ARR_TYPE_REF = new TypeReference<QueryResult[]>(){};
	
	
	
	
	
	/** Empty string array const */
	public static final String[] EMPTY_STR_ARR = {};
	/** Empty dps set const */
	public static final SortedSet<long[]> EMPTY_DPS_SET = Collections.unmodifiableSortedSet(new TreeSet<long[]>());
	
	
	/**
	 * <p>Title: DPSComparator</p>
	 * <p>Description: DPS array comparator</p> 
	 * @author Whitehead (nwhitehead AT heliosdev DOT org)
	 * <p><code>com.heliosapm.streams.metrichub.results.QueryResult.DPSComparator</code></p>
	 */
	private static class DPSComparator implements Comparator<long[]> {
		@Override
		public int compare(long[] o1, long[] o2) {			
			return o1[0] < o2[0] ? -1 : o1[0] > o2[0] ? 1 : 0;  
		}
	}
	
	/** DPS comparator */
	public static final Comparator<long[]> DPS_COMPARATOR = new DPSComparator();
	
	
	
	
	public QueryResult(final String metricName, final Map<String, String> tags, final String[] aggregatedTags, final Set<long[]> dps) {		
		this.metricName = metricName;
		this.tags = tags;
		this.aggregatedTags = aggregatedTags;
		this.dps.addAll(dps);

	}
	
	
	
	/**
	 * Converts all of the either timestamps (index 0) or values (index 1) to a LongStream  
	 * @param index the index of the dps items to stream: 0 for timestamp, 1 for values
	 * @return a LongStream
	 */
	protected LongStream dpsStream(final int index) {
		return StreamSupport.stream(dps.spliterator(), true).flatMapToLong(arr -> LongStream.of(arr[index])).parallel();
	}

	/**
	 * Returns a long stream of the ms timestamps of the result's DPS
	 * @return a long stream
	 */
	public LongStream times() {
		return dpsStream(0).sorted();
	}
	
	/**
	 * Returns a long stream of the timestamps of the result's DPS as java dates
	 * @return a stream of dates
	 */
	public Stream<Date> dates() {
		return dpsStream(0).parallel().mapToObj(t -> new Date(t)).sorted();
	}
	
	/**
	 * Returns a long stream of the values of the result's DPS
	 * @return a long stream
	 */
	public LongStream values() {
		return dpsStream(1);
	}
	
	/**
	 * Returns a summary of the values in the DPS for this result
	 * @return a long value summary
	 */
	public LongSummaryStatistics valueSummary() {
		return dpsStream(1).summaryStatistics();
	}
	
	
//	public String toString() {
//		final StringBuilder b = new StringBuilder("QResult [\n\tm:")
//			.append(metricName).append(tags)
//			.append("\n\tAggTags:").append(Arrays.toString(aggregatedTags))
//			.append("\n\tDPS:");
//		for(long[] dp: dps) {
//			b.append("\n\t\t").append(new Date(dp[0])).append(" : ").append(dp[1]);
//		}
//		b.append("\n]");
//		return b.toString();
//	}
	
	public String toString() {
		if(dps.isEmpty()) {
			return new StringBuilder(metricName).append(":").append(tags).append(", aggtags:").append(Arrays.toString(aggregatedTags)).append(", dps:").append(dps.size()).toString();
		} else {
			return new StringBuilder(metricName).append(":").append(tags).append(", aggtags:").append(Arrays.toString(aggregatedTags)).append(", dps:").append(dps.size())
					.append(", summary:").append(valueSummary()).toString();
		}
	}
	
	
	public static void main(String[] args) {
		try {
			JSONOps.registerDeserializer(QueryResult.class, new QueryResultDeserializer());
			JSONOps.registerDeserializer(QueryResult[].class, new QueryResultArrayDeserializer());
			
			final String jsonResponse = URLHelper.getTextFromURL("./src/test/resources/responses/response-multi.js");
			final QueryResult[] qrs = JSONOps.parseToObject(jsonResponse, QueryResult[].class);
			System.out.println("Results:" + qrs.length);
			for(QueryResult q: qrs) {
				//System.out.println(q);
			}
		} catch (Exception ex) {
			ex.printStackTrace(System.err);
		}
	}

}
