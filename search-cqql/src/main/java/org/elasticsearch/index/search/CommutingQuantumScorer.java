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

import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.elasticsearch.index.query.QueryBuilder;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.math.MathException;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;

/**
 * Scores documents over a given {@link CommutingQuantumQuery}.
 */
public class CommutingQuantumScorer extends Scorer {

    private Weight[] conditionWeights;
    private LeafReaderContext context;
    private DocIdSetIterator iterator;
    private int scoreRound;

    /**
     * Constructor for a {@link CommutingQuantumScorer} object, instantiated by {@link CommutingQuantumWeight}.
     * @param weight the {@link CommutingQuantumWeight} object implying this {@link CommutingQuantumScorer}
     * @param context additional query information (cf. {@link QueryBuilder})
     * @param scoreRound the current round of scoring
     */
    protected CommutingQuantumScorer(CommutingQuantumWeight weight, LeafReaderContext context, int scoreRound) {
        super(weight);
        this.conditionWeights = new Weight[((CommutingQuantumWeight) this.weight).getConditionWeights().length];
        for (int i = 0; i < this.conditionWeights.length; i++) {
            this.conditionWeights[i] = ((CommutingQuantumWeight) this.weight).getConditionWeights()[i];
        }
        this.context = context;
        this.iterator = DocIdSetIterator.all(context.reader().maxDoc()); // cf. MatchAllDocsQuery
        this.scoreRound = scoreRound;
    }

    @Override
    public DocIdSetIterator iterator() {
        return this.iterator;
    }

    @Override
    public float getMaxScore(int upTo) throws IOException {
        return ((CommutingQuantumWeight) this.weight).getMaxScore();
    }

    /**
     * Creates an array of all variables from an {@link IExpr} object containing a list of all variables.
     * <br>
     * <i>Regexes might be too slow. Therefore, the use of Symja functions will be implemented in the future!</i>
     * @param variables the {@link IExpr} object which has been created using Symja's Variables(...) function
     * @return an array of all variables used
     */
    private static String[] getVariablesArray(IExpr variables) throws MathException, StackOverflowError, OutOfMemoryError {
        return variables.toString()
            .replaceFirst("\\{", "")
            .replaceFirst("}", "")
            .replaceAll(" ", "")
            .split(",");
    }

    /**
     * Helper method. Looks for the largest value within a matrix, rounding it up to the next integer,
     * before dividing all values of the matrix by this integer.
     * @param matrix a two-dimensional matrix containing {@code float} values
     */
    private static void normalizeMatrix(float[][] matrix) {
        int maxValue = 0;
        for (int q = 0; q < matrix.length; q++)
            for (int d = 0; d < matrix[q].length; d++)
                maxValue = (int) Math.ceil(Math.max((float) maxValue, matrix[q][d]));
        if (maxValue != 0)
            for (int q = 0; q < matrix.length; q++)
                for (int d = 0; d < matrix[q].length; d++)
                    matrix[q][d] /= maxValue;
    }

    /**
     * Helper method for computing the scores in the form of a two-dimensional {@code scoreMatrix}.
     * @param maxDocs Equivalent to the maximum number of documents indexed
     *                (cf. {@code maxDoc()} in {@link org.apache.lucene.index.IndexReader}).
     * @throws IOException
     */
    private void computeScores(int maxDocs) throws IOException {
        // create a MatchAllDocsQuery in order to retrieve all indexed documents' id values
        TopDocs allDocs = ((CommutingQuantumWeight) this.weight).searcher.searchAfter(
            null, new MatchAllDocsQuery(), maxDocs, Sort.INDEXORDER, true
        );
        ((CommutingQuantumWeight) this.weight).allDocIDs = new ArrayList<>();
        for (int i = 0; i < allDocs.scoreDocs.length; i++) {
            int curSdocID = allDocs.scoreDocs[i].doc;
            Document curDoc = ((CommutingQuantumWeight) this.weight).searcher.doc(curSdocID);
            String curDocID = curDoc.getField("_id").toString();
            ((CommutingQuantumWeight) this.weight).allDocIDs.add(curDocID);
        }
        // instantiate a score matrix over every query and every indexed document
        ((CommutingQuantumWeight) this.weight).scoreMatrix = new float[this.conditionWeights.length][((CommutingQuantumWeight) this.weight).allDocIDs.size()];
        for (int i = 0; i < ((CommutingQuantumWeight) this.weight).scoreMatrix.length; i++)
            for (int j = 0; j < ((CommutingQuantumWeight) this.weight).scoreMatrix[i].length; j++)
                ((CommutingQuantumWeight) this.weight).scoreMatrix[i][j] = 0;
        // enter the score for every condition into the score matrix now
        for (int i = 0; i < ((CommutingQuantumWeight) this.weight).scoreMatrix.length; i++) {
            TopDocs docs = ((CommutingQuantumWeight) this.weight).searcher.searchAfter(
                null, this.conditionWeights[i].getQuery(), // search for all result documents ("null") for one query ("...[i].getQuery()")
                ((CommutingQuantumWeight) this.weight).scoreMatrix[i].length, Sort.INDEXORDER, true // then sort them in index order ("Sort.INDEXORDER") and retrieve the score ("true")
            );
            ScoreDoc[] scoreDocs = docs.scoreDocs;
            for (int sdoc = 0; sdoc < scoreDocs.length; sdoc++) {
                int sdocID = scoreDocs[sdoc].doc;
                Document doc = ((CommutingQuantumWeight) this.weight).searcher.doc(sdocID);
                String docID = doc.getField("_id").toString();
                // find out which scoreMatrix position belongs to the determined docID and put the score there
                ((CommutingQuantumWeight) this.weight).scoreMatrix[i][((CommutingQuantumWeight) this.weight).allDocIDs.indexOf(docID)] = scoreDocs[sdoc].score;
            }
            // ... and all not-matched documents keep the score value "0.0"
        }
        normalizeMatrix(((CommutingQuantumWeight) this.weight).scoreMatrix); // finally, normalize the scores
        ((CommutingQuantumWeight) this.weight).scoresComputed = true;

    }

    @Override
    public float score() throws IOException {
        assert docID() != DocIdSetIterator.NO_MORE_DOCS; // asserts that there are docs left to be scored

        String calc = ((CommutingQuantumQuery) this.weight.getQuery()).getCalcString();
        float calcScore = -1;
        int maxDocs = ((CommutingQuantumWeight) this.weight).searcher.getIndexReader().maxDoc();
        int numDocs = ((CommutingQuantumWeight) this.weight).searcher.getIndexReader().numDocs();

        if (((CommutingQuantumWeight) this.weight).scoringDocsLeft == -111) {
            ((CommutingQuantumWeight) this.weight).scoringDocsLeft = numDocs;
        } else {
            ((CommutingQuantumWeight) this.weight).scoringDocsLeft--;
        }

        if (!((CommutingQuantumWeight) this.weight).scoresComputed)
            computeScores(maxDocs);

        // calculate the score by evaluating the String "calc"
        calcScore = (float) AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    ExprEvaluator calcEvaluator = new ExprEvaluator(false, (short) 100);
                    IExpr calcExpr;
                    IExpr varsExpr = calcEvaluator.eval("Variables(" + calc + ")");
                    String[] variables = getVariablesArray(varsExpr);
                    String curCondType = ""; // the type of the current condition of the formula
                    String curQueryType = ""; // the type of the current query of the list of queries
                    String curCondValue = "";
                    String curQueryValue = "";
                    float curScore = 0; // the score of the current query

                    for (int i = 0; i < variables.length; i++) {
                        // the type of a condition is defined to be located before the first $$ symbols
                        curCondType = variables[i].split("\\$\\$")[0];
                        curCondValue = variables[i];
                        if (curCondType.equals("w")) { // weighting condition
                            String curScoreStr = variables[i].split("\\$\\$")[1];
                            // a single $ symbol is defined to represent the decimal point
                            curScoreStr = curScoreStr.replaceFirst("\\$","\\.");
                            curScore = Float.valueOf(curScoreStr);
                        } else {
                            int j = 0;
                            do { // as long as the names of the condition type and the query type don't match (see below)
                                // get the class name of the current weight's parent query and convert it to lower case
                                curQueryType = conditionWeights[j].getQuery().getClass().getSimpleName().toLowerCase();
                                curQueryValue = ((CommutingQuantumQuery) getWeight().getQuery()).getConditionStrings()[j].toLowerCase();
                                // the document which must be scored is determined from the number of documents which still need to be scored
                                curScore = ((CommutingQuantumWeight) getWeight()).scoreMatrix[j][numDocs - ((CommutingQuantumWeight) weight).scoringDocsLeft];
                                j++;
                            } while (!(curQueryType.equals(curCondType) && curQueryValue.equals(curCondValue)) && !(j > conditionWeights.length));
                        }
                        calcEvaluator.eval(variables[i] + " = " + curScore);
                    }
                    calcExpr = calcEvaluator.eval(calc); // evaluate and therefore calculate the score
                    return Float.valueOf(calcExpr.toString()); // the calculated score
                } catch (SyntaxError se) {
                    // catch Symja parser errors here
                    System.out.println(se.getMessage());
                } catch (MathException me) {
                    // catch Symja math errors here
                    System.out.println(me.getMessage());
                } catch (final Exception ex) {
                    System.out.println(ex.getMessage());
                } catch (final StackOverflowError soe) {
                    System.out.println(soe.getMessage());
                } catch (final OutOfMemoryError oome) {
                    System.out.println(oome.getMessage());
                }
                return -1;
            }
        });
        // update maxScore value
        if (calcScore > ((CommutingQuantumWeight) this.weight).getMaxScore()) {
            ((CommutingQuantumWeight) this.weight).setMaxScore(calcScore);
        }
        return calcScore;
    }

    @Override
    public int docID() {
        return this.iterator.docID();
    }
}
