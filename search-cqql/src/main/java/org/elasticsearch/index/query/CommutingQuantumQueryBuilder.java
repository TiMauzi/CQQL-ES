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

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.index.search.CommutingQuantumQuery;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.math.MathException;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a query that applies the rules of quantum mechanics by using the commuting quantum query language (CQQL).
 * The implementation follows the concepts described in
 * <a href="https://doi.org/10.1007/s00778-007-0070-1">
 *   Schmitt, I. QQL: A DB&amp;IR Query Language. The VLDB Journal 17, pp. 39-56 (2008).
 * </a><br>
 * The structure of this class is similar to {@link BoolQueryBuilder} and therefore it inherits from said class.
 * However, it aims at fixing some logical problems concerning the compliance with the Boolean algebra rules.
 */
public class CommutingQuantumQueryBuilder extends BoolQueryBuilder {
    public static final String NAME = "commuting_quantum";

    private static final ParseField MUST = new ParseField("must");
    private static final ParseField MUST_NOT = new ParseField("must_not");
    private static final ParseField SHOULD = new ParseField("should");

    static ArrayList<String> literalList = new ArrayList<>();
    static ArrayList<QueryBuilder> queryList = new ArrayList<>();
    private static String[] literals;
    private static QueryBuilder[] queryBuilders;
    private static Query[] queries;

    /**
     * Empty constructor.
     */
    public CommutingQuantumQueryBuilder() {
        super();
    }

    /**
     * Read from a stream.
     * @param in the stream containing the input.
     */
    public CommutingQuantumQueryBuilder(StreamInput in) throws IOException {
        super(in);
    }

    /**
     * Needs to be {@code public} due to its usage in {@link org.elasticsearch.plugin.query.CQQLPlugin}.<br>
     * <i>Note:</i> Possible errors on {@code must/should/mustNot} seem to be bugs (same problem in {@link BoolQueryBuilder}).
     */
    public static final ObjectParser<CommutingQuantumQueryBuilder, Integer> PARSER = new ObjectParser<>("commuting_quantum", CommutingQuantumQueryBuilder::new);
    static {
        PARSER.declareObjectArrayOrNull((builder, clauses) -> clauses.forEach(builder::must), (p, c) -> parseInnerQueryBuilder(p),
            MUST);
        PARSER.declareObjectArrayOrNull((builder, clauses) -> clauses.forEach(builder::should), (p, c) -> parseInnerQueryBuilder(p),
            SHOULD);
        PARSER.declareObjectArrayOrNull((builder, clauses) -> clauses.forEach(builder::mustNot), (p, c) -> parseInnerQueryBuilder(p),
            MUST_NOT);
        // ParseField FILTER (etc.) is not defined for commuting quantum queries.
        PARSER.declareString(CommutingQuantumQueryBuilder::queryName, NAME_FIELD);
        PARSER.declareFloat(CommutingQuantumQueryBuilder::boost, BOOST_FIELD);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    /**
     * Behaves similarly to the {@code doToQuery} method of a regular {@link QueryBuilder}, but does not create a Lucene {@link Query}.
     * Instead, this prepares and creates a {@link CommutingQuantumQuery},
     * introduced by {@link org.elasticsearch.plugin.query.CQQLPlugin}, as follows:
     * <ol>
     *   <li>The {@link List}{@code <}{@link QueryBuilder}{@code >} objects implied by {@code super.must()}, {@code super.should()},
     *   and {@code super.mustNot()} are to be transcribed into a logical formula, which is represented by a {@link String}.</li>
     *   <li>That very formula needs to be normalized according to CQQL, as described in:
     *      <a href="https://doi.org/10.1007/s00778-007-0070-1">
     *          Schmitt, I. QQL: A DB&amp;IR Query Language. The VLDB Journal 17, p. 49 (2008).
     *      </a>
     *   </li>
     * </ol>
     * @param context additional query information (cf. {@link QueryBuilder})
     * @return an evaluable {@link CommutingQuantumQuery}, or&mdash;if only {@code "True"} or {@code "False"} remains&mdash;a {@link MatchAllDocsQuery}
     * or a {@link MatchNoDocsQuery}, respectively.
     */
    @Override
    protected Query doToQuery(QueryShardContext context) throws IOException {
        String queryString = transcribe(super.must(), super.should(), super.mustNot(), context);
        literals = new String[literalList.size()];
        queryBuilders = new QueryBuilder[queryList.size()];
        literalList.toArray(literals);
        queryList.toArray(queryBuilders);

        //System.out.println("Before normalize(queryString): " + queryString);
        queryString = doNormalize(queryString); // CQQL normalization
        //System.out.println("After normalize(queryString): " + queryString);

        queries = new Query[queryBuilders.length];
        for (int qb = 0; qb < queryBuilders.length; qb++) {
            queries[qb] = queryBuilders[qb].toQuery(context);
        }
        if (queryString.equals("True") || queryString.isEmpty()) {
            return new MatchAllDocsQuery();
        } else {
            if (queryString.equals("False")) {
                return new MatchNoDocsQuery();
            }
        }

        return new CommutingQuantumQuery(queryString, literals, queries);
    }

    /**
     * Checks, whether the given {@link QueryBuilder} does <i>not</i> resemble an atomic condition.
     * In case <code>instanceof CommutingQuantumQueryBuilder</code> holds, the method is recursively called for the child {@link QueryBuilder}.
     * Else, the condition is being rewritten in a <a href="https://github.com/axkr/symja_android_library/">Symja</a> {@link IExpr}-like format.
     * @param mustConditionList the list of {@link QueryBuilder}s resembling the {@code must} conditions of a query
     * @param shouldConditionList the list of {@link QueryBuilder}s resembling the {@code should} conditions of a query
     * @param mustNotConditionList the list of {@link QueryBuilder}s resembling the {@code must_not} conditions of a query
     * @param context additional query information (cf. {@link QueryBuilder})
     * @return the concatenated formula by calling {@code concatenateConditionStrings((mustString, shouldString, mustNotString))}
     */
    private static String transcribe(List<QueryBuilder> mustConditionList, List<QueryBuilder> shouldConditionList, List<QueryBuilder> mustNotConditionList,
                                     QueryShardContext context) {
        List<?>[] conditionLists = {mustConditionList, shouldConditionList, mustNotConditionList};
        String mustString = "";
        String shouldString = "";
        String mustNotString = "";
        int e = 0;
        for (List<?> conditionList : conditionLists) {
            for (int i = 0; i < conditionList.size(); i++) {
                String literal = "";
                float weightValue = ((QueryBuilder) conditionList.get(i)).boost();
                String weightLiteral = "";
                if (weightValue != DEFAULT_BOOST) {
                    switch (e) {
                        case 0: case 2: weightLiteral += " || "; break;
                        case 1: weightLiteral += " && "; break;
                    }
                    String[] positions = String.valueOf(weightValue).split("\\.");
                    weightLiteral += "!(w$$" + positions[0] + "$" + positions[1] + ")";
                    ((QueryBuilder) conditionList.get(i)).boost(DEFAULT_BOOST); // reset boost because it's saved elsewhere now
                }
                if (conditionList.get(i) instanceof CommutingQuantumQueryBuilder) {
                    literal = transcribe(
                        ((CommutingQuantumQueryBuilder) (conditionList.get(i))).must(),
                        ((CommutingQuantumQueryBuilder) (conditionList.get(i))).should(),
                        ((CommutingQuantumQueryBuilder) (conditionList.get(i))).mustNot(),
                        context
                    );
                } else {
                    AtomicQueryBuilderName queryBuilderName = AtomicQueryBuilderName.valueOf(((QueryBuilder) conditionList.get(i)).getName());
                    switch (queryBuilderName) {
                        // TODO implement other atomic queryBuilders hereafter
                        case match:
                            try {
                                literal = ((QueryBuilder) conditionList.get(i)).toQuery(context).getClass().getSimpleName().toLowerCase() + "$$";
                            } catch (IOException ioe) {
                                System.err.println(ioe);
                                System.out.println("Trying to use Elasticsearch's QueryBuilder definition instead.");
                                literal = queryBuilderName + "query$$";
                            }
                            literal += ((MatchQueryBuilder) conditionList.get(i)).value().toString();
                            break;
                        case term:
                            try {
                                literal = ((QueryBuilder) conditionList.get(i)).toQuery(context).getClass().getSimpleName().toLowerCase() + "$$";
                            } catch (IOException ioe) {
                                System.err.println(ioe);
                                System.out.println("Trying to use Elasticsearch's QueryBuilder definition instead.");
                                literal = queryBuilderName + "query$$";
                            }
                            literal += ((TermQueryBuilder) conditionList.get(i)).value().toString();
                            break;
                        case match_all:
                            literal = "True";
                            break;
                        case match_none:
                            literal = "False";
                            break;
                        // case ...
                        default:
                    }
                    // bookkeeping of all different (!) literals
                    if (!literalList.contains(literal)) {
                        literalList.add(literal);
                        queryList.add((QueryBuilder) conditionList.get(i));
                    }
                }
                if (!literal.isEmpty()) { // avoiding empty parentheses
                    if ((e == 0 && mustString.isEmpty())
                        || (e == 1 && shouldString.isEmpty())
                        || (e == 2 && mustNotString.isEmpty())) {
                        switch (e) {
                            case 0:
                                mustString = "((" + literal + ")" + weightLiteral + ")";
                                break;
                            case 1:
                                shouldString = "((" + literal + ")" + weightLiteral + ")";
                                break;
                            case 2:
                                mustNotString = "(!(" + literal + ")" + weightLiteral + ")";
                                break;
                        }
                    } else {
                        switch (e) {
                            case 0:
                                mustString += " && ((" + literal + ")" + weightLiteral + ")";
                                break;
                            case 1:
                                shouldString += " || ((" + literal + ")" + weightLiteral + ")";
                                break;
                            case 2:
                                mustNotString += " && (!(" + literal + ")" + weightLiteral + ")";
                                break;
                        }
                    }
                }
            }
            e++;
        }
        return concatenateConditionStrings(mustString, shouldString, mustNotString);
    }

    /**
     * Puts together all conjunction, disjunction, and negation conditions into one single formula {@link String}.
     * @param mustString the conjunction conditions
     * @param shouldString the disjunction conditions
     * @param mustNotString the negation conditions in a conjunction
     * @return a formula resembling the input query in a {@link IExpr}-like format (cf.  <a href="https://github.com/axkr/symja_android_library/">Symja</a>).
     */
    private static String concatenateConditionStrings(String mustString, String shouldString, String mustNotString) {
        String completeString;
        if (mustString.isEmpty()) {
            if (shouldString.isEmpty()) {
                if (mustNotString.isEmpty()) {  // - - -
                    completeString = "";
                } else {                        // - - +
                    completeString = mustNotString;
                }
            } else {
                if (mustNotString.isEmpty()) {  // - + -
                    completeString = shouldString;
                } else {                        // - + +
                    completeString = "(" + shouldString + ") && (" + mustNotString + ")";
                }
            }
        } else {
            if (shouldString.isEmpty()) {
                if (mustNotString.isEmpty()) {  // + - -
                    completeString = mustString;
                } else {                        // + - +
                    completeString = "(" + mustString + ") && (" + mustNotString + ")";
                }
            } else {
                if (mustNotString.isEmpty()) { // + + -
                    completeString = "(" + mustString + ") && (" + shouldString + ")";
                } else {                       // + + +
                    completeString = "(" + mustString + ") && (" + shouldString + ") && (" + mustNotString + ")";
                }
            }
        }
        return completeString;
    }

    /**
     * A wrapper method containing the privileged {@code normalize} method.
     * @param queryString the logical formula that ought to be normalized
     * @return the normalized query formula
     */
    private static String doNormalize(String queryString) {
        return (String) AccessController.doPrivileged(new PrivilegedAction<Object>() {

            /**
             * Step 3 of the CQQL normalization algorithm, treating overlaps of conditions (cf.
             * <a href="https://doi.org/10.1007/s00778-007-0070-1">
             * Schmitt, I. QQL: A DB&amp;IR Query Language. The VLDB Journal 17, p. 49 (2008)
             * </a>).
             * @param exprString the current state of the logical formula that is currently being normalized
             * @param exprEvaluator the {@code exprEvaluator} used in {@code normalize}
             * @return the formula with a resolved overlap in conditions
             */
            private String solveOverlaps(String exprString, ExprEvaluator exprEvaluator) throws MathException, StackOverflowError, OutOfMemoryError {

                boolean existsOverlap = false;
                String overlapLiteral = "";
                StringBuilder posExprStringBuilder = new StringBuilder();
                StringBuilder negExprStringBuilder = new StringBuilder();
                String posExprString;
                String negExprString;
                IExpr exprNew;
                String exprStringNew;
                IExpr posExpr;
                IExpr negExpr;

                // define each conjunction as an array element by splitting at "||"
                String[] conjunctions = exprString.split("\\|\\|");
                String[][] conjunctionTerms = new String[conjunctions.length][];
                // define each term of each conjunction as an array element by splitting at "&&"
                for (int i = 0; i < conjunctions.length; i++) {
                    conjunctions[i] = conjunctions[i].replaceFirst("\\(", ""); // remove the opening parenthesis
                    conjunctions[i] = conjunctions[i].replaceAll("\\)$", ""); // remove the closing parenthesis
                    conjunctionTerms[i] = conjunctions[i].split("&&");
                }

                // Step 3: check for overlaps
                for (int i = 0; i < (conjunctions.length - 1); i++) {               // from every conjunction...
                    for (String conjunctionTerm1 : conjunctionTerms[i]) {           // take each term...
                        for (int j = i+1; j < conjunctions.length; j++) {           // compare it to all combinations...
                            for (String conjunctionTerm2 : conjunctionTerms[j]) {   // with other terms

                                // check, whether the terms (without possible negations) are equal
                                if (conjunctionTerm1.replaceAll("!", "")
                                    .equals(conjunctionTerm2.replaceAll("!", ""))) {

                                    // Step 3a: Let overlapLiteral be a literal common to at least two conjunctions
                                    existsOverlap = true;// found overlap!
                                    overlapLiteral = conjunctionTerm1;
                                    break;

                                }

                            }
                            if (existsOverlap) break;
                        }
                        if (existsOverlap) break;
                    }
                    if (existsOverlap) break;
                }

                if (existsOverlap) {
                    // Step 3b: Replace all conjunctions x by "(o && x) || (!o && x)"
                    String[] posConjunctions = new String[conjunctions.length];
                    String[] negConjunctions = new String[conjunctions.length];
                    for (int i = 0; i < conjunctions.length; i++) {
                        posConjunctions[i] = "(" + overlapLiteral + ") && (" + conjunctions[i] + ")";
                        posExprStringBuilder.append("(").append(posConjunctions[i]).append(") ||");
                        negConjunctions[i] = "(!(" + overlapLiteral + ")) && (" + conjunctions[i] + ")";
                        negExprStringBuilder.append("(").append(negConjunctions[i]).append(") ||");
                    }
                    posExprString = posExprStringBuilder.toString();
                    negExprString = negExprStringBuilder.toString();

                    // Step 3c: Simplify with idempotence, invertibility, and absorption to obtain exprNew
                    // construct disjunction of posExprString and negExprString (neutral element of disjunctions (False) at end)
                    exprNew = exprEvaluator.eval("BooleanConvert(" + posExprString + negExprString + "False)");
                    exprStringNew = exprNew.toString();
                    posExprStringBuilder = new StringBuilder();
                    negExprStringBuilder = new StringBuilder();

                    // Step 3d: Create ((overlapLiteral && posExpr) || (!overlapLiteral && negExpr)) from exprNew
                    // define each conjunction as an array element
                    String[] conjunctionsNew = exprStringNew.split("\\|\\|");
                    for (String conjunction : conjunctionsNew) {
                        // remove overlapLiteral from conjunction and add the neutral element of conjunctions (True) instead
                        if (conjunction.contains("!" + overlapLiteral)) {
                            negExprStringBuilder.append("(").append(conjunction.replace("!" + overlapLiteral, "True")).append(") || ");
                        } else {
                            posExprStringBuilder.append("(").append(conjunction.replace(overlapLiteral, "True")).append(") || ");
                        }
                    }
                    // neutral element of disjunctions (False) at end
                    posExprStringBuilder.append("False");
                    negExprStringBuilder.append("False");
                    posExprString = posExprStringBuilder.toString();
                    negExprString = negExprStringBuilder.toString();
                    posExpr = exprEvaluator.eval(posExprString);
                    negExpr = exprEvaluator.eval(negExprString);
                    posExprStringBuilder = new StringBuilder(posExpr.toString());
                    negExprStringBuilder = new StringBuilder(negExpr.toString());
                    // recursive call
                    //exprStringNew = "(" + overlapLiteral + " && (" + solveOverlaps(posExprStringBuilder.toString(), exprEvaluator)
                    //    + ")) || (!" + overlapLiteral + " && (" + solveOverlaps(negExprStringBuilder.toString(), exprEvaluator) + "))";
                    // recursive call where exclusive disjunctions are replaced by an addition immediately
                    exprStringNew = "(" + overlapLiteral + " && (" + solveOverlaps(posExprStringBuilder.toString(), exprEvaluator)
                        + ")) + (!" + overlapLiteral + " && (" + solveOverlaps(negExprStringBuilder.toString(), exprEvaluator) + "))";
                } else {
                    // Step 4: De Morgan's laws for innermost disjunctions
                    // BooleanConvert to remove unnecessary neutral elements
                    exprNew = exprEvaluator.eval("ReplaceRepeated(BooleanConvert(" + exprString + "), deMorganDisCon)");
                    exprStringNew = exprNew.toString();
                    // exit condition
                }
                return exprStringNew;

            }

            /**
             * Contains an implementation of the CQQL transformation algorithm for normalizing CQQL queries (cf.
             * <a href="https://doi.org/10.1007/s00778-007-0070-1">
             * Schmitt, I. QQL: A DB&amp;IR Query Language. The VLDB Journal 17, p. 49 (2008)
             * </a>).
             * @param queryString the logical formula containing the input query in a {@link IExpr}-like format
             * @return the normalized formula
             */
            private String normalize(String queryString) {
                String formulaString = queryString;
                // String formulaString = "(termquery$$fuchs&&(termquery$$schaf||(!termquery$$schaf&&termquery$$huhn)))||(!termquery$$fuchs&&termquery$$schaf&&termquery$$wolf)";
                try {
                    ExprEvaluator formulaEvaluator = new ExprEvaluator(false, (short) 100);
                    IExpr formula;
                    // De Morgan:
                    formulaEvaluator.eval("deMorganDisCon = {x_ || y_ :> !(!x && !y)}");
                    formulaEvaluator.eval("deMorganConDis = {x_ && y_ :> !(!x || !y)}");

                    // Step 1: transform expression e into disjunctive normal form
                    // Step 2: apply idempotence and invertibility rules
                    if (formulaString.isEmpty()) {
                        formula = formulaEvaluator.eval("True");
                        // skip the other steps in case of empty expression...
                    } else {
                        formula = formulaEvaluator.eval("BooleanConvert(" + formulaString + ")");
                        formulaString = solveOverlaps(formula.toString(), formulaEvaluator); // contains Step 3
                        formula = formulaEvaluator.eval(formulaString);
                    }
                    formulaString = formula.toString();
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
                return formulaString;
            }

            public Object run() {
                return normalize(queryString);
            }

        });
    }
}
