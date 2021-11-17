/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

/**
 * This enumeration is needed to detect queries that are to be considered "atomic" within a {@link org.elasticsearch.index.search.CommutingQuantumQuery} object.
 * The query types are grouped according to the <a href="https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html">Elasticsearch documentation's categorization</a>.
 */
public enum AtomicQueryBuilderName {
    // Compound queries:
    bool, boosting, constant_score, dis_max, function_score,
    // Full text queries:
    intervals, match, match_bool_prefix, match_phrase, match_phrase_prefix, multi_match, common, query_string, simple_query_string,
    // Term-level queries:
    exists, fuzzy, ids, prefix, range, regexp, term, terms, terms_set, type, wildcard,
    // Geo queries:
    geo_bounding_box, geo_distance, geo_polygon, geo_shape,
    // Shape queries:
    shape,
    // Span queries:
    span_containing, field_masking_span, span_first, span_multi, span_near, span_or, span_term, span_within,
    // Joining queries:
    nested, has_child, has_parent, parent_id,
    // Specialized queries:
    distance_feature, more_like_this, percolate, rank_feature, script, script_score, wrapper, pinned,
    // Other queries:
    match_all, match_none
}
