/*-
 * #%L
 * SPARQL2NL
 * %%
 * Copyright (C) 2015 - 2021 Data and Web Science Research Group (DICE)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.aksw.sparql2nl.naturallanguagegeneration;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.aksw.sparql2nl.queryprocessing.GenericType;
import org.aksw.sparql2nl.queryprocessing.TypeExtractor;
import org.aksw.triple2nl.converter.DefaultIRIConverter;
import org.aksw.triple2nl.converter.LiteralConverter;
import org.dllearner.kb.sparql.SparqlEndpoint;

import simplenlg.features.Feature;
import simplenlg.features.Tense;
import simplenlg.framework.CoordinatedPhraseElement;
import simplenlg.framework.DocumentElement;
import simplenlg.framework.LexicalCategory;
import simplenlg.framework.NLGElement;
import simplenlg.framework.NLGFactory;
import simplenlg.lexicon.Lexicon;
import simplenlg.phrasespec.NPPhraseSpec;
import simplenlg.phrasespec.SPhraseSpec;
import simplenlg.realiser.english.Realiser;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.SortCondition;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.aggregate.AggCountVar;
import org.apache.jena.sparql.expr.aggregate.Aggregator;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementUnion;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 *
 * @author ngonga
 */
public class SimpleNLG implements Sparql2NLConverter {

    Lexicon lexicon;
    NLGFactory nlgFactory;
    Realiser realiser;
    private DefaultIRIConverter uriConverter;
    private LiteralConverter literalConverter;
    private FilterExpressionConverter expressionConverter;
    
    public static final String ENTITY = "owl#thing";
    public static final String VALUE = "value";
    public static final String UNKNOWN = "valueOrEntity";
    public List<NLGElement> phrases;
    private SparqlEndpoint endpoint;

    public SimpleNLG(SparqlEndpoint endpoint) {
        this.endpoint = endpoint;

        lexicon = Lexicon.getDefaultLexicon();
        nlgFactory = new NLGFactory(lexicon);
        realiser = new Realiser(lexicon);
        
        uriConverter = new DefaultIRIConverter(endpoint);
        literalConverter = new LiteralConverter(uriConverter);
        expressionConverter = new FilterExpressionConverter(uriConverter, literalConverter);
        
    }

    /** Converts the representation of the query as Natural Language Element into
     * free text.
     * @param query Input query
     * @return Text representation
     */
    @Override
    public String getNLR(Query query) {
        String output = realiser.realiseSentence(convert2NLE(query));
        output = output.replaceAll(Pattern.quote("\n"), "");
        if (!output.endsWith(".")) {
            output = output + ".";
        }
        return output;
    }

    /** Generates a natural language representation for a query
     * 
     * @param query Input query
     * @return Natural Language Representation
     */
    @Override
    public DocumentElement convert2NLE(Query query) {
        phrases = new ArrayList<>();
        if (query.isSelectType() || query.isAskType()) {
            return convertSelectAndAsk(query);
        } else if (query.isDescribeType()) {
            return convertDescribe(query);
        } else {
            SPhraseSpec head = nlgFactory.createClause();
            head.setSubject("This framework");
            head.setVerb("support");
            head.setObject("the input query");
            head.setFeature(Feature.NEGATED, true);
            DocumentElement sentence = nlgFactory.createSentence(head);
            DocumentElement doc = nlgFactory.createParagraph(Arrays.asList(sentence));
            return doc;
        }
    }

    /** Generates a natural language representation for SELECT queries
     * 
     * @param query Input query
     * @return Natural Language Representation
     */
    public DocumentElement convertSelectAndAsk(Query query) {
        // List of sentences for the output
        List<DocumentElement> sentences = new ArrayList<>();
//        System.out.println("Input query = " + query);
        // preprocess the query to get the relevant types
        TypeExtractor tEx = new TypeExtractor(endpoint);
        Map<String, Set<String>> typeMap = tEx.extractTypes(query);
//        System.out.println("Processed query = " + query);
        // contains the beginning of the query, e.g., "this query returns"
        SPhraseSpec head = nlgFactory.createClause();
        String conjunction = "such that";
        NLGElement body;
        NLGElement postConditions;

        List<Element> whereElements = getWhereElements(query);
        List<Element> optionalElements = getOptionalElements(query);
        // first sort out variables
        Set<String> projectionVars = typeMap.keySet();
        Set<String> whereVars = getVars(whereElements, projectionVars);
        // case we only have stuff such as rdf:type queries
        if (whereVars.isEmpty()) {
            whereVars = projectionVars;
        }
        Set<String> optionalVars = getVars(optionalElements, projectionVars);
        //important. Remove variables that have already been declared in first
        //sentence from second sentence
        for (String var : whereVars) {
            if (optionalVars.contains(var)) {
                optionalVars.remove(var);
            }
        }

        //process SELECT queries
        if (query.isSelectType()) {
            //process head
            //we could create a lexicon from which we could read these
            head.setSubject("This query");
            if (!tEx.isCount()) {
                head.setVerb("retrieve");
            } else {
                head.setVerb("retrieve the number of");
            }
        } //process ASK queries
        else {
            //process factual ask queries (no variables at all)
            if (typeMap.isEmpty()) {
                head.setSubject("This query");
                head.setVerb("ask whether");
                head.setObject(getNLFromElements(whereElements));
                head.setFeature(Feature.SUPRESSED_COMPLEMENTISER, true);
                phrases.add(head);
                sentences.add(nlgFactory.createSentence(head));
                return nlgFactory.createParagraph(sentences);
            }
            //process head
            //we could create a lexicon from which we could read these
            head.setSubject("This query");
            head.setVerb("ask for the existence of");
        }
        NLGElement e = processTypes(typeMap, whereVars, tEx.isCount(), query.isDistinct());
        head.setObject(e);
        //now generate body
        if (!whereElements.isEmpty()) {
            body = getNLFromElements(whereElements);
            //now add conjunction
            CoordinatedPhraseElement phrase1 = nlgFactory.createCoordinatedPhrase(head, body);
            phrase1.setConjunction("such that");
            // add as first sentence
            sentences.add(nlgFactory.createSentence(phrase1));
            phrases.add(phrase1);
            //this concludes the first sentence. 
        } else {
            sentences.add(nlgFactory.createSentence(head));
            phrases.add(head);
        }

        // The second sentence deals with the optional clause (if it exists)
        if (optionalElements != null && !optionalElements.isEmpty()) {
            //the optional clause exists
            //if no supplementary projection variables are used in the clause 
            if (optionalVars.isEmpty()) {
                SPhraseSpec optionalHead = nlgFactory.createClause();
                optionalHead.setSubject("it");
                optionalHead.setVerb("retrieve");
                optionalHead.setObject("data");
                optionalHead.setFeature(Feature.CUE_PHRASE, "Additionally, ");
                NLGElement optionalBody = getNLFromElements(optionalElements);
                CoordinatedPhraseElement optionalPhrase = nlgFactory.createCoordinatedPhrase(optionalHead, optionalBody);
                optionalPhrase.setConjunction("such that");
                optionalPhrase.addComplement("if such exist");
                sentences.add(nlgFactory.createSentence(optionalPhrase));

            } //if supplementary projection variables are used in the clause 
            else {
                SPhraseSpec optionalHead = nlgFactory.createClause();
                optionalHead.setSubject("it");
                optionalHead.setVerb("retrieve");
                optionalHead.setObject(processTypes(typeMap, optionalVars, query.isDistinct(), query.isDistinct()));
                optionalHead.setFeature(Feature.CUE_PHRASE, "Additionally, ");
                if (!optionalElements.isEmpty()) {
                    NLGElement optionalBody;
                    optionalBody = getNLFromElements(optionalElements);
                    //now add conjunction
                    CoordinatedPhraseElement optionalPhrase = nlgFactory.createCoordinatedPhrase(optionalHead, optionalBody);
                    optionalPhrase.setConjunction("such that");
                    // add as second sentence
                    optionalPhrase.addComplement("if such exist");
                    sentences.add(nlgFactory.createSentence(optionalPhrase));
                    phrases.add(optionalPhrase);
                    //this concludes the second sentence. 
                } else {
                    optionalHead.addComplement("if such exist");
                    sentences.add(nlgFactory.createSentence(optionalHead));
                    phrases.add(optionalHead);
                }
            }
        }

        //The last sentence deals with the result modifiers
        if (query.hasHaving()) {
            SPhraseSpec modifierHead = nlgFactory.createClause();
            modifierHead.setSubject("it");
            modifierHead.setVerb("return exclusively");
            modifierHead.setObject("results");
            modifierHead.getObject().setPlural(true);
            modifierHead.setFeature(Feature.CUE_PHRASE, "Moreover, ");
            List<Expr> expressions = query.getHavingExprs();
            CoordinatedPhraseElement phrase = nlgFactory.createCoordinatedPhrase(modifierHead, getNLFromExpressions(expressions));
            phrase.setConjunction("such that");
            sentences.add(nlgFactory.createSentence(phrase));
        }
        if (query.hasOrderBy()) {
            SPhraseSpec order = nlgFactory.createClause();
            order.setSubject("The results");
            order.getSubject().setPlural(true);
            order.setVerb("be ordered by");
            List<SortCondition> sc = query.getOrderBy();
            if (sc.size() == 1) {
                Expr expr = sc.get(0).getExpression();
                if (expr instanceof ExprVar) {
                    ExprVar ev = (ExprVar) expr;
                    order.setObject(ev.toString());
                }
                if (sc.get(0).direction < 0) {
                    order.addComplement("in descending order");
                } else {
                    order.addComplement("in ascending order");
                }
            }
            phrases.add(order);
            sentences.add(nlgFactory.createSentence(order));
        }
        if (query.hasLimit()) {
            SPhraseSpec limitOffset = nlgFactory.createClause();
            long limit = query.getLimit();
            if (query.hasOffset()) {
                long offset = query.getOffset();
                limitOffset.setSubject("The query");
                limitOffset.setVerb("return");
                limitOffset.setObject("results between number " + limit + " and " + (offset+limit));
            } else {
                limitOffset.setSubject("The query");
                limitOffset.setVerb("return");
                if (limit > 1) {
                    if (query.hasOrderBy()) {
                        limitOffset.setObject("at most the first " + limit + " results");
                    } else {
                        limitOffset.setObject("at most " + limit + " results");
                    }
                } else {
                    if (query.hasOrderBy()) {
                        limitOffset.setObject("at most the first result");
                    } else {
                        limitOffset.setObject("at most one result");
                    }
                }
            }
            phrases.add(limitOffset);
            sentences.add(nlgFactory.createSentence(limitOffset));
        }

        DocumentElement result = nlgFactory.createParagraph(sentences);
        return result;
    }

    /** Fetches all elements of the query body, i.e., of the WHERE clause of a 
     * query
     * @param query Input query
     * @return List of elements from the WHERE clause
     */
    private static List<Element> getWhereElements(Query query) {
        List<Element> result = new ArrayList<>();
        ElementGroup elt = (ElementGroup) query.getQueryPattern();
        for (int i = 0; i < elt.getElements().size(); i++) {
            Element e = elt.getElements().get(i);
            if (!(e instanceof ElementOptional)) {
                result.add(e);
            }
        }
        return result;
    }

    /** Fetches all elements of the optional, i.e., of the OPTIONAL clause. 
     * query
     * @param query Input query
     * @return List of elements from the OPTIONAL clause if there is one, else null
     */
    private static List<Element> getOptionalElements(Query query) {
        ElementGroup elt = (ElementGroup) query.getQueryPattern();
        for (int i = 0; i < elt.getElements().size(); i++) {
            Element e = elt.getElements().get(i);
            if (e instanceof ElementOptional) {
                return ((ElementGroup) ((ElementOptional) e).getOptionalElement()).getElements();
            }
        }
        return new ArrayList<>();
    }

    /** Takes a DBPedia class and returns the correct label for it
     * 
     * @param className Name of a class
     * @return Label
     */
    public NPPhraseSpec getNPPhrase(String className, boolean plural) {
        NPPhraseSpec object = null;
        if (className.equals(OWL.Thing.getURI())) {
            object = nlgFactory.createNounPhrase(GenericType.ENTITY.getNlr());
        } else if (className.equals(RDFS.Literal.getURI())) {
            object = nlgFactory.createNounPhrase(GenericType.VALUE.getNlr());
        } else if (className.equals(RDF.Property.getURI())) {
            object = nlgFactory.createNounPhrase(GenericType.RELATION.getNlr());
        } else if (className.equals(RDF.type.getURI())) {
            object = nlgFactory.createNounPhrase(GenericType.TYPE.getNlr());
        } else {
            String label = uriConverter.convert(className);
            if (label != null) {
                object = nlgFactory.createNounPhrase(label);
            } else {
                object = nlgFactory.createNounPhrase(GenericType.ENTITY.getNlr());
            }
        }
        object.setPlural(plural);
        return object;
    }


    private NLGElement processTypes(Map<String, Set<String>> typeMap, Set<String> vars, boolean count, boolean distinct) {
        List<NPPhraseSpec> objects = new ArrayList<>();
        //process the type information to create the object(s)    
        for (String s : typeMap.keySet()) {
            if (vars.contains(s)) {
                // contains the objects to the sentence                
                NPPhraseSpec object;
                object = nlgFactory.createNounPhrase("?" + s);
                Set<String> types = typeMap.get(s);
                if (types.size() == 1) {
                    NPPhraseSpec np = getNPPhrase(types.iterator().next(), true);
                    if (distinct) {
                        np.addModifier("distinct");
                    }
                    object.addPreModifier(np);
                } else {
                    Iterator<String> typeIterator = types.iterator();
                    String type0 = typeIterator.next();
                    String type1 = typeIterator.next();
                    NPPhraseSpec np0 = getNPPhrase(type0, true);
//                        if (distinct) {
//                            np0.addModifier("distinct");
//                        }
                    NPPhraseSpec np1 = getNPPhrase(type1, true);
//                        if (distinct) {
//                            np1.addModifier("distinct");
//                        }
                    CoordinatedPhraseElement cpe = nlgFactory.createCoordinatedPhrase(np0, np1);
                    while (typeIterator.hasNext()) {
                        NPPhraseSpec np = getNPPhrase(typeIterator.next(), true);
//                        if (distinct) {
//                            np.addModifier("distinct");
//                        }
                        cpe.addCoordinate(np);
                    }
                    cpe.setConjunction("as well as");
                    if (distinct) {
                        cpe.addPreModifier("distinct");
                    }
                    object.addPreModifier(cpe);
                }
                object.setFeature(Feature.CONJUNCTION, "or");
                objects.add(object);
            }
        }
        if (objects.size() == 1) {
            //if(count) objects.get(0).addPreModifier("the number of");
            return objects.get(0);
        } else {
            CoordinatedPhraseElement cpe = nlgFactory.createCoordinatedPhrase(objects.get(0), objects.get(1));
            if (objects.size() > 2) {
                for (int i = 2; i < objects.size(); i++) {
                    cpe.addCoordinate(objects.get(i));
                }
            }
            //if(count) cpe.addPreModifier("the number of");
            return cpe;
        }
    }

    public DocumentElement convertDescribe(Query query) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** Processes a list of elements. These can be elements of the where clause or 
     * of an optional clause
     * @param e List of query elements
     * @return Conjunctive natural representation of the list of elements.
     */
    public NLGElement getNLFromElements(List<Element> e) {
        if (e.isEmpty()) {
            return null;
        }
        if (e.size() == 1) {
            return getNLFromSingleClause(e.get(0));
        } else {
            CoordinatedPhraseElement cpe;
            cpe = nlgFactory.createCoordinatedPhrase(getNLFromSingleClause(e.get(0)), getNLFromSingleClause(e.get(1)));
            for (int i = 2; i < e.size(); i++) {
                cpe.addCoordinate(getNLFromSingleClause(e.get(i)));
            }
            cpe.setConjunction("and");
            
            return cpe;
        }
    }

    public NLGElement getNLForTripleList(List<Triple> triples, String conjunction) {
        if (triples.isEmpty()) {
            return null;
        }
        if (triples.size() == 1) {
            return getNLForTriple(triples.get(0));
        } else {
            CoordinatedPhraseElement cpe;
            Triple t0 = triples.get(0);
            Triple t1 = triples.get(1);
            cpe = nlgFactory.createCoordinatedPhrase(getNLForTriple(t0), getNLForTriple(t1));
            for (int i = 2; i < triples.size(); i++) {
                cpe.addCoordinate(getNLForTriple(triples.get(i)));
            }
            cpe.setConjunction(conjunction);
            return cpe;
        }
    }

    public NLGElement getNLFromSingleClause(Element e) {
        if (e instanceof ElementPathBlock) {
            ElementPathBlock epb = (ElementPathBlock) e;
            List<Triple> triples = new ArrayList<>();

            //get all triples. We assume that the depth of union is always 1
            for (TriplePath tp : epb.getPattern().getList()) {
                Triple t = tp.asTriple();
                triples.add(t);
            }
            return getNLForTripleList(triples, "and");
        } // if clause is union clause then we generate or statements
        else if (e instanceof ElementUnion) {
            CoordinatedPhraseElement cpe;
            //cast to union
            ElementUnion union = (ElementUnion) e;
            List<Triple> triples = new ArrayList<>();

            //get all triples. We assume that the depth of union is always 1
            for (Element atom : union.getElements()) {
                ElementPathBlock epb = ((ElementPathBlock) (((ElementGroup) atom).getElements().get(0)));
                if (!epb.isEmpty()) {
                    Triple t = epb.getPattern().get(0).asTriple();
                    triples.add(t);
                }
            }
            return getNLForTripleList(triples, "or");
        } // if it's a filter
        else if (e instanceof ElementFilter) {
            SPhraseSpec p = nlgFactory.createClause();
            ElementFilter filter = (ElementFilter) e;
            Expr expr = filter.getExpr();
            return getNLFromSingleExpression(expr);
        }
        return null;
    }

    public SPhraseSpec getNLForTriple2(Triple t) {
        SPhraseSpec p = nlgFactory.createClause();
        //process subject
        if (t.getSubject().isVariable()) {
            p.setSubject(t.getSubject().toString());
        } else {
            p.setSubject(getNPPhrase(t.getSubject().toString(), false));
        }

        //process predicate
        if (t.getPredicate().isVariable()) {
            p.setVerb("be related via " + t.getPredicate().toString() + " to");
        } else {
            p.setVerb(getVerbFrom(t.getPredicate()));
        }

        //process object
        if (t.getObject().isVariable()) {
            p.setObject(t.getObject().toString());
        } else if (t.getObject().isLiteral()) {
            p.setObject(t.getObject().getLiteralLexicalForm());
        } else {
            p.setObject(getNPPhrase(t.getObject().toString(), false));
        }

        p.setFeature(Feature.TENSE, Tense.PRESENT);
        return p;
    }

    public SPhraseSpec getNLForTriple(Triple t) {
        SPhraseSpec p = nlgFactory.createClause();
        //process predicate then return subject is related to
        if (t.getPredicate().isVariable()) {
            if (t.getSubject().isVariable()) {
                p.setSubject(t.getSubject().toString());
            } else {
                p.setSubject(getNPPhrase(t.getSubject().toString(), false));
            }
            p.setVerb("be related via " + t.getPredicate().toString() + " to");
            if (t.getObject().isVariable()) {
                p.setObject(t.getObject().toString());
            } else {
                p.setObject(getNPPhrase(t.getObject().toString(), false));
            }
        } else {
            NLGElement subj;
            if (t.getSubject().isVariable()) {
                subj = nlgFactory.createWord(t.getSubject().toString(), LexicalCategory.NOUN);
            } else {
                subj = nlgFactory.createWord(uriConverter.convert(t.getSubject().toString()), LexicalCategory.NOUN);
            }
            //        subj.setFeature(Feature.POSSESSIVE, true);            
            //        PhraseElement np = nlgFactory.createNounPhrase(subj, getEnglishLabel(t.getPredicate().toString()));
            p.setSubject(realiser.realise(subj) + "\'s " + uriConverter.convert(t.getPredicate().toString()));
            p.setVerb("be");
            if (t.getObject().isVariable()) {
                p.setObject(t.getObject().toString());
            } else if (t.getObject().isLiteral()) {
                p.setObject(t.getObject().getLiteralLexicalForm());
            } else {
                p.setObject(getNPPhrase(t.getObject().toString(), false));
            }
        }
        p.setFeature(Feature.TENSE, Tense.PRESENT);
        return p;
    }

    private String getVerbFrom(Node predicate) {
        return "test";
        //throw new UnsupportedOperationException("Not yet implemented");
    }

    private Set<String> getVars(List<Element> elements, Set<String> projectionVars) {
        Set<String> result = new HashSet<>();
        for (Element e : elements) {
            for (String var : projectionVars) {
                if (e.toString().contains("?" + var)) {
                    result.add(var);
                }
            }
        }
        return result;
    }

    private NLGElement getNLFromSingleExpression(Expr expr) {
        SPhraseSpec p = nlgFactory.createClause();
        return expressionConverter.convert(expr);
        //process REGEX
        /*if (expr instanceof E_Regex) {
        E_Regex expression;
        expression = (E_Regex) expr;
        String text = expression.toString();
        text = text.substring(6, text.length() - 1);
        String var = text.substring(0, text.indexOf(","));
        String pattern = text.substring(text.indexOf(",") + 1);
        p.setSubject(var);
        p.setVerb("match");
        p.setObject(pattern);
        } else if (expr instanceof ExprFunction1) {
        boolean negated = false;
        if (expr instanceof E_LogicalNot) {
        expr = ((E_LogicalNot) expr).getArg();
        negated = true;
        }
        if (expr instanceof E_Bound) {
        p.setSubject(((E_Bound) expr).getArg().toString());
        p.setVerb("exist");
        }
        if (negated) {
        p.setFeature(Feature.NEGATED, true);
        }
        } //process language filter
        else if (expr instanceof E_Equals) {
        E_Equals expression;
        expression = (E_Equals) expr;
        String text = expression.toString();
        text = text.substring(1, text.length() - 1);
        String[] split = text.split("=");
        String arg1 = split[0].trim();
        String arg2 = split[1].trim();
        if (arg1.startsWith("lang")) {
        String var = arg1.substring(5, arg1.length() - 1);
        p.setSubject(var);
        p.setVerb("be in");
        if (arg2.contains("en")) {
        p.setObject("English");
        }
        } else {
        p.setSubject(arg1);
        p.setVerb("equal");
        p.setObject(arg2);
        }
        } else if (expr instanceof ExprFunction2) {
        Expr left = ((ExprFunction2) expr).getArg1();
        Expr right = ((ExprFunction2) expr).getArg2();
        
        //invert if right is variable or aggregation and left side not 
        boolean inverted = false;
        if (!left.isVariable() && (right.isVariable() || right instanceof ExprAggregator)) {
        Expr tmp = left;
        left = right;
        right = tmp;
        inverted = true;
        }
        
        //handle subject
        NLGElement subject = null;
        if (left instanceof ExprAggregator) {
        subject = getNLGFromAggregation((ExprAggregator) left);
        } else {
        if (left.isFunction()) {
        ExprFunction function = left.getFunction();
        if (function.getArgs().size() == 1) {
        left = function.getArg(1);
        }
        }
        subject = nlgFactory.createNounPhrase(left.toString());
        }
        p.setSubject(subject);
        //handle object
        if (right.isFunction()) {
        ExprFunction function = right.getFunction();
        if (function.getArgs().size() == 1) {
        right = function.getArg(1);
        }
        }
        if (right.isVariable()) {
        p.setObject(right.toString());
        } else if (right.isConstant()) {
        if (right.getConstant().isIRI()) {
        p.setObject(getEnglishLabel(right.getConstant().getNode().getURI()));
        } else if (right.getConstant().isLiteral()) {
        p.setObject(right.getConstant().asNode().getLiteralLexicalForm());
        }
        }
        //handle verb resp. predicate
        String verb = null;
        if (expr instanceof E_GreaterThan) {
        if (inverted) {
        verb = "be less than";
        } else {
        verb = "be greater than";
        }
        } else if (expr instanceof E_GreaterThanOrEqual) {
        if (inverted) {
        verb = "be less than or equal to";
        } else {
        verb = "be greater than or equal to";
        }
        } else if (expr instanceof E_LessThan) {
        if (inverted) {
        verb = "be greater than";
        } else {
        verb = "be less than";
        }
        } else if (expr instanceof E_LessThanOrEqual) {
        if (inverted) {
        verb = "be greater than or equal to";
        } else {
        verb = "be less than or equal to";
        }
        } else if (expr instanceof E_NotEquals) {
        if (left instanceof E_Lang) {
        verb = "be in";
        if (right.isConstant() && right.getConstant().asString().equals("en")) {
        p.setObject("English");
        }
        
        } else {
        verb = "be equal to";
        }
        p.setFeature(Feature.NEGATED, true);
        }
        p.setVerb(verb);
        } //not equals
        else {
        return null;
        }
        return p;*/
    }

    private NLGElement getNLGFromAggregation(ExprAggregator aggregationExpr) {
        SPhraseSpec p = nlgFactory.createClause();
        Aggregator aggregator = aggregationExpr.getAggregator();
        Expr expr = aggregator.getExprList().get(0);
        if (aggregator instanceof AggCountVar) {
            p.setSubject("the number of " + expr);
        }
        return p.getSubject();
    }

    private NLGElement getNLFromExpressions(List<Expr> expressions) {
        List<NLGElement> nlgs = new ArrayList<>();
        NLGElement elt;
        for (Expr e : expressions) {
            elt = getNLFromSingleExpression(e);
            if (elt != null) {
                nlgs.add(elt);
            }
        }
        //now process 
        if (nlgs.isEmpty()) {
            return null;
        }
        if (nlgs.size() == 1) {
            return nlgs.get(0);
        } else {
            CoordinatedPhraseElement cpe;
            cpe = nlgFactory.createCoordinatedPhrase(nlgs.get(0), nlgs.get(1));
            for (int i = 2; i < nlgs.size(); i++) {
                cpe.addComplement(nlgs.get(i));
            }
            cpe.setConjunction("and");
            return cpe;
        }
    }


    public static void main(String args[]) {
        String query2 = "PREFIX res: <http://dbpedia.org/resource/> "
                + "PREFIX dbo: <http://dbpedia.org/ontology/> "
                + "SELECT DISTINCT ?height "
                + "WHERE { res:Claudia_Schiffer dbo:height ?height . "
                + "FILTER(\"1.0e6\"^^<http://www.w3.org/2001/XMLSchema#double> <= ?height)}";

        String query = "PREFIX dbo: <http://dbpedia.org/ontology/> "
                + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
                + "PREFIX res: <http://dbpedia.org/resource/> "
                + "SELECT ?uri ?x "
                + "WHERE { "
                + "{res:Abraham_Lincoln dbo:deathPlace ?uri} "
                + "UNION {res:Abraham_Lincoln dbo:birthPlace ?uri} . "
                + "?uri rdf:type dbo:Place. "
                + "FILTER regex(?uri, \"France\").  "
                + "FILTER (lang(?uri) = 'en')"
                + "OPTIONAL { ?uri dbo:Name ?x }. "
                + "}";
        String query3 = "PREFIX dbo: <http://dbpedia.org/ontology/> "
                + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
                + "PREFIX yago: <http://dbpedia.org/class/yago/> "
                + "SELECT COUNT(DISTINCT ?uri) "
                //+ "SELECT ?uri "
                + "WHERE { ?uri rdf:type yago:EuropeanCountries . ?uri dbo:governmentType ?govern . "
                + "FILTER regex(?govern,'monarchy') . "
                //+ "FILTER (!BOUND(?date))"
                + "}";
        String query4 = "PREFIX dbo: <http://dbpedia.org/ontology/> "
                + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
                + "SELECT DISTINCT ?uri ?string "
                + "WHERE { ?cave rdf:type dbo:Cave . "
                + "?cave dbo:location ?uri . "
                + "?uri rdf:type dbo:Country . "
                + "OPTIONAL { ?uri rdfs:label ?string. FILTER (lang(?string) = 'en') } }"
                + " GROUP BY ?uri ?string "
                + "HAVING (COUNT(?cave) > 2)";
        String query5 = "PREFIX dbo: <http://dbpedia.org/ontology/> "
                + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
                + "SELECT DISTINCT ?uri "
                + "WHERE { ?cave rdf:type dbo:Cave . "
                + "?cave dbo:location ?uri . "
                + "?uri rdf:type dbo:Country . "
                + "?uri dbo:writer ?y . FILTER(!BOUND(?cave))"
                + "?cave dbo:location ?x } ";

        String query6 = "PREFIX dbo: <http://dbpedia.org/ontology/> "
                + "PREFIX dbp: <http://dbpedia.org/property/> "
                + "PREFIX res: <http://dbpedia.org/resource/> "
                + "ASK WHERE { { res:Batman_Begins dbo:starring res:Christian_Bale . } "
                + "UNION { res:Batman_Begins dbp:starring res:Christian_Bale . } }";

        String query7 = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>"
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
                + "PREFIX foaf: <http://xmlns.com/foaf/0.1/>"
                + "PREFIX dbo: <http://dbpedia.org/ontology/>"
                + "PREFIX res: <http://dbpedia.org/resource/>"
                + "PREFIX yago: <http://dbpedia.org/class/yago/>"
                + "SELECT DISTINCT ?uri ?string "
                + "WHERE { "
                + "	?uri rdf:type yago:RussianCosmonauts."
                + "        ?uri rdf:type yago:FemaleAstronauts ."
                + "OPTIONAL { ?uri rdfs:label ?string. FILTER (lang(?string) = 'en') }"
                + "}";

        String query8 = "PREFIX  rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
                + "PREFIX  dbo:  <http://dbpedia.org/ontology/> "
                + "PREFIX  dbp:  <http://dbpedia.org/property/> "
                + "PREFIX  dbp:  <http://dbpedia.org/ontology/> "
                + "PREFIX  rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
                + "SELECT DISTINCT  ?uri ?string "
                + "WHERE { ?uri rdf:type dbo:Country . "
                + "{?uri dbp:birthPlace ?language} UNION {?union dbo:birthPlace ?language} "
                + "OPTIONAL { ?uri rdfs:label ?string "
                + "FILTER ( lang(?string) = \'en\' )} } "
                + "GROUP BY ?uri ?string "
                + "ORDER BY DESC(?language) "
                + "LIMIT 10 OFFSET 20";

        String query9 = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
                + "PREFIX foaf: <http://xmlns.com/foaf/0.1/> "
                + "PREFIX mo: <http://purl.org/ontology/mo/> "
                + "SELECT DISTINCT ?artisttype "
                + "WHERE {"
                + "?artist foaf:name 'Liz Story'."
                + "?artist rdf:type ?artisttype ."
                + "FILTER (?artisttype != mo:MusicArtist)"
                + "}";

        String query10 = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>"
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
                + "PREFIX yago: <http://dbpedia.org/class/yago/>"
                + "PREFIX dbo: <http://dbpedia.org/ontology/>"
                + "PREFIX dbp: <http://dbpedia.org/property/>"
                + "PREFIX res: <http://dbpedia.org/resource/>"
                + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>"
                + "SELECT DISTINCT ?uri ?string "
                + "WHERE {"
                + "?uri rdf:type dbo:Person ."
                + "{ ?uri rdf:type yago:PresidentsOfTheUnitedStates. } "
                + "UNION "
                + "{ ?uri rdf:type dbo:President."
                + "?uri dbp:title res:President_of_the_United_States. }"
                + "?uri rdfs:label ?string."
                + "FILTER (?string >\"1970-01-01\"^^xsd:date && lang(?string) = 'en' && !regex(?string,'Presidency','i') && !regex(?string,'and the')) ."
                + "}";


        try {
//            SparqlEndpoint ep = new SparqlEndpoint(new URL("http://greententacle.techfak.uni-bielefeld.de:5171/sparql"));
            SparqlEndpoint ep = new SparqlEndpoint(new URL("https://dbpedia.org/sparql"));
            SimpleNLG snlg = new SimpleNLG(ep);
            Query sparqlQuery = QueryFactory.create(query10, Syntax.syntaxARQ);
            DocumentElement elt = snlg.convert2NLE(sparqlQuery);            
            System.out.println("Simple NLG: " + elt);
            System.out.println("Simple NLG: " + snlg.getNLR(sparqlQuery));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
