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

package org.elasticsearch.index.search;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

/**
 * Serves as an administrator class, organizing the {@link CommutingQuantumScorer} instances using the
 * {@code scorer} method.
 */
public class CommutingQuantumWeight extends Weight {

    final CommutingQuantumQuery query;
    final IndexSearcher searcher;
    final ScoreMode scoreMode;
    final float boost;

    protected ArrayList<String> allDocIDs;
    protected float[][] scoreMatrix;
    protected boolean scoresComputed;

    private Weight[] conditionWeights;
    private float maxScore = 0;
    private int scoreRound = -1; // cf. scorer method

    public int scoringDocsLeft = -111;

    /**
     * Standard constructor, creating a {@link CommutingQuantumWeight} object using the given {@link CommutingQuantumQuery},
     * an {@link IndexSearcher}, the chosen {@link ScoreMode} and, if applicable, a weighting value.
     * @param query the {@link CommutingQuantumQuery} whose results need to be scored
     * @param searcher the {@link IndexSearcher}, looking through all indexed documents
     * @param scoreMode determines, how many and which results need to be considered (e.g. the COMPLETE results or the TOP_DOCS only)
     * @param boost the weighting value
     */
    public CommutingQuantumWeight(CommutingQuantumQuery query, IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        super(query);
        this.query = query;
        this.searcher = searcher;
        this.scoreMode = scoreMode;
        this.boost = boost;

        this.conditionWeights = new Weight[this.query.getConditions().length];
        for (int i = 0; i < this.conditionWeights.length; i++) {
            try {
                conditionWeights[i] = searcher.createWeight(this.query.getConditions()[i], this.scoreMode, 1);
            } catch (UnsupportedOperationException uoe) {
                System.err.println(uoe.getMessage());
            }
        }
    }

    @Override
    public void extractTerms(Set<Term> terms) {
        // Doesn't seem to be necessary, but might have to be implemented in another version.
        // (See also Lucene's ConstantScoreWeight, where this is also not implemented.)
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc) throws IOException {
        Scorer scorer = scorer(context);
        return Explanation.match(scorer.score(), "The calculation formula used for scoring is: " + ((CommutingQuantumQuery) this.getQuery()).getCalcString());
    }

    @Override
    public Scorer scorer(LeafReaderContext context) throws IOException {
        scoreRound++; // increase global counter for every time this function is used
        return new CommutingQuantumScorer(this, context, scoreRound);
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
        return false;
    }

    public Weight[] getConditionWeights() {
        return conditionWeights;
    }

    public float getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(float maxScore) {
        this.maxScore = maxScore;
    }
}
