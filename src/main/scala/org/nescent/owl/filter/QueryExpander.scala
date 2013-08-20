package org.nescent.owl.filter

import scala.collection.JavaConversions._
import scala.collection.Map
import scala.collection.Set
import scala.collection.mutable
import org.apache.log4j.BasicConfigurator
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.obolibrary.macro.ManchesterSyntaxTool
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.expression.OWLEntityChecker
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.OWLAnnotationProperty
import org.semanticweb.owlapi.model.OWLClass
import org.semanticweb.owlapi.model.OWLClassExpression
import org.semanticweb.owlapi.model.OWLDataProperty
import org.semanticweb.owlapi.model.OWLDatatype
import org.semanticweb.owlapi.model.OWLNamedIndividual
import org.semanticweb.owlapi.model.OWLObjectProperty
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.util.DefaultPrefixManager
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary
import com.hp.hpl.jena.graph.NodeFactory
import com.hp.hpl.jena.graph.Node_Literal
import com.hp.hpl.jena.graph.Node_Variable
import com.hp.hpl.jena.query.Query
import com.hp.hpl.jena.query.QueryFactory
import com.hp.hpl.jena.sparql.core.TriplePath
import com.hp.hpl.jena.sparql.expr.E_OneOf
import com.hp.hpl.jena.sparql.expr.ExprList
import com.hp.hpl.jena.sparql.expr.ExprVar
import com.hp.hpl.jena.sparql.expr.nodevalue.NodeValueNode
import com.hp.hpl.jena.sparql.syntax.ElementFilter
import com.hp.hpl.jena.sparql.syntax.ElementGroup
import com.hp.hpl.jena.sparql.syntax.ElementPathBlock
import com.hp.hpl.jena.sparql.syntax.ElementVisitorBase
import com.hp.hpl.jena.sparql.syntax.ElementWalker
import org.semanticweb.owlapi.model.OWLEntity

/**
 * Processes SPARQL queries containing triple patterns with embedded OWL class expressions. 
 * Class expressions are evaluated and queried against an OWL reasoner, and the triple
 * pattern is replaced with a FILTER(?x IN (...)).
 */
class QueryExpander(reasoner: OWLReasoner) {

	private val factory = OWLManager.getOWLDataFactory();
	val SUBCLASS_OF = OWLRDFVocabulary.RDFS_SUBCLASS_OF.getIRI().toString();
	val EQUIVALENT_CLASS = OWLRDFVocabulary.OWL_EQUIVALENT_CLASS.getIRI().toString();
	val TYPE = OWLRDFVocabulary.RDF_TYPE.getIRI().toString();
	val OWL_FILTER_NS = "http://purl.org/phenoscape/owl-filter/syntax#";
	val MANCHESTER = OWL_FILTER_NS + "omn";
	val OWLXML = OWL_FILTER_NS + "owx";
	val FUNCTIONAL = OWL_FILTER_NS + "ofn";
	val SYNTAXES = Set(MANCHESTER, OWLXML, FUNCTIONAL);

	def expandQueryString(query: String): String = {
			val parsedQuery = QueryFactory.create(query);
			return expandQuery(parsedQuery).toString();
	}

	def expandQuery(query: Query): Query = {
			val prefixMap = query.getPrefixMapping().getNsPrefixMap();
			ElementWalker.walk(query.getQueryPattern(), new ElementVisitorBase() {
				override
				def visit(group: ElementGroup): Unit = {
						processElementGroup(group, prefixMap);
				}
			});
			return query;
	}

	private
	def processElementGroup(group: ElementGroup, prefixes: Map[String, String]): Unit = {
			for (pathBlock <- group.getElements().filter(_.isInstanceOf[ElementPathBlock]).map(_.asInstanceOf[ElementPathBlock])) {
				val patterns = pathBlock.getPattern().iterator();
				for (pattern <- patterns) {
					val filterOpt = 
							(pattern.getSubject(), pattern.getPredicate().getURI(), pattern.getObject()) match {
							case (variable: Node_Variable, 
									SUBCLASS_OF, 
									expression: Node_Literal) if SYNTAXES(expression.getLiteralDatatypeURI()) => 
							runQueryAndMakeFilter(querySubClasses, expression, prefixes, variable);
							case (expression: Node_Literal, 
									SUBCLASS_OF, 
									variable: Node_Variable) if SYNTAXES(expression.getLiteralDatatypeURI()) =>
							runQueryAndMakeFilter(querySuperClasses, expression, prefixes, variable);
							case (variable: Node_Variable, 
									EQUIVALENT_CLASS, 
									expression: Node_Literal) if SYNTAXES(expression.getLiteralDatatypeURI()) =>
							runQueryAndMakeFilter(queryEquivalentClasses, expression, prefixes, variable);
							case (expression: Node_Literal, 
									EQUIVALENT_CLASS, 
									variable: Node_Variable) if SYNTAXES(expression.getLiteralDatatypeURI()) =>
							runQueryAndMakeFilter(queryEquivalentClasses, expression, prefixes, variable);
							case (variable: Node_Variable, 
									TYPE,
									expression: Node_Literal) if SYNTAXES(expression.getLiteralDatatypeURI()) =>
							runQueryAndMakeFilter(queryIndividuals, expression, prefixes, variable);
							case _ => None;
					}
					for (filter <- filterOpt) {
						patterns.remove();
						group.addElement(filter);
					}
				}
			}
	}

	private
	def runQueryAndMakeFilter(queryFunction: (OWLClassExpression => Set[_ <:OWLEntity]), classExpression: Node_Literal, prefixes: Map[String, String], variable: Node_Variable): Option[ElementFilter] = {
		val parsedExpression = parseExpression(classExpression, prefixes);
		parsedExpression match {
		case Some(expression) => Option(makeFilter(variable, queryFunction(expression)));
		case None => None;
		}
	} 

	def makeFilter(variable: Node_Variable, classes: Iterable[OWLEntity]): ElementFilter = {
		val nodes = classes.map(term => new NodeValueNode(NodeFactory.createURI(term.getIRI().toString())));
		val oneOf = new E_OneOf(new ExprVar(variable), new ExprList(nodes.toList));
		new ElementFilter(oneOf);
	}

	def parseExpression(literal: Node_Literal, prefixes: Map[String, String]): Option[OWLClassExpression] = {
		val expression = literal.getLiteralLexicalForm();
		literal.getLiteralDatatypeURI() match {
		case MANCHESTER => ManchesterSyntaxClassExpressionParser.parse(expression, prefixes);
		case OWLXML => OWLXMLClassExpressionParser.parse(expression, prefixes);
		case FUNCTIONAL => parseFunctional(expression, prefixes);
		}
	}

	private
	def parseFunctional(expression: String, prefixes: Map[String, String]): Option[OWLClassExpression] = {
		return None; //TODO
	}

	private
	def querySubClasses(expression: OWLClassExpression): Set[OWLClass] = {
		reasoner.getSubClasses(expression, false).getFlattened();
	}

	private
	def queryEquivalentClasses(expression: OWLClassExpression): Set[OWLClass] = {
		reasoner.getEquivalentClasses(expression).getEntities();
	}

	private
	def querySuperClasses(expression: OWLClassExpression): Set[OWLClass] = {
		reasoner.getSuperClasses(expression, false).getFlattened();

	}

	private
	def queryIndividuals(expression: OWLClassExpression): Set[OWLNamedIndividual] = {
		reasoner.getInstances(expression, false).getFlattened();
	}

}