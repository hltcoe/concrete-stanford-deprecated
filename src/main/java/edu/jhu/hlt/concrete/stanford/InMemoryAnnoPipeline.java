package edu.jhu.hlt.concrete.stanford;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Properties;

import nu.xom.Attribute;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.Serializer;
import edu.jhu.agiga.AgigaDocument;
import edu.jhu.agiga.AgigaPrefs;
import edu.jhu.agiga.BytesAgigaDocumentReader;
import edu.jhu.hlt.concrete.Concrete.UUID;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreAnnotations.IndexAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentenceIndexAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.ParserAnnotatorUtils;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.trees.TreeCoreAnnotations.TreeAnnotation;
import edu.stanford.nlp.trees.semgraph.SemanticGraph;
import edu.stanford.nlp.trees.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.util.CoreMap;

/**
 * An in-memory version of the Annotated Gigaword pipeline, using only the
 * Stanford CORE NLP tools.
 * 
 * This class allows AgigaDocument objects to be created entirely in-memory from
 * an (unannotated) input represented as a Stanford Annotation object.
 * 
 * @author mgormley
 */
public class InMemoryAnnoPipeline {

    private static final boolean debug = false;
    private static final boolean do_deps = true;

    private StanfordCoreNLP pipeline;

    public InMemoryAnnoPipeline(boolean onlyTokenize) {
        Properties props = new Properties();
        String annotatorList = "tokenize";
        if (!onlyTokenize)
            annotatorList += ", pos, lemma, parse, ner, dcoref";
        if (debug) {
            System.err.println("Using annotators " + annotatorList);
        }
        props.put("annotators", annotatorList);
        pipeline = new StanfordCoreNLP(props);
    }
    
    public AgigaDocument annotate(Annotation annotation) throws IOException {
        return annotate(pipeline, annotation);
    }
    
    public static AgigaDocument annotate(StanfordCoreNLP pipeline, Annotation annotation) throws IOException {

        // Run all Annotators on this text
        pipeline.annotate(annotation);

        // Convert to an XML document.
        Document xmlDoc = stanfordToXML(pipeline, annotation);

        // Convert the XML document to an AgigaDocument.
        AgigaDocument agigaDoc = xmlToAgigaDoc(xmlDoc);

        return agigaDoc;
    }

    /** This method assumes only one <DOC/> is contained in the xmlDoc. */
    private static AgigaDocument xmlToAgigaDoc(Document xmlDoc) throws UnsupportedEncodingException, IOException {
        // Serialize to a byte array.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Serializer ser = new Serializer(baos, "UTF-8");
        ser.setIndent(2);
        ser.setMaxLength(0);
        // The anno-pipeline used a customized version of the
        // nu.xom.Serializer that gave public access to otherwise protected
        // methods. Instead, we just write the entire document at once as above.
        ser.write(xmlDoc);
        ser.flush();

        AgigaPrefs agigaPrefs = new AgigaPrefs();
        agigaPrefs.setAll(true);
        BytesAgigaDocumentReader adr = new BytesAgigaDocumentReader(baos.toByteArray(), agigaPrefs);
        AgigaDocument agigaDoc = adr.next();
        if (adr.hasNext()) {
            throw new IllegalStateException("Multiple documents found.");
        }
        return agigaDoc;
    }

    /**
     * NOTICE: Copied and modified version from edu.jhu.annotation.GigawordAnnotator.
     * 
     * Create the XML document, using the base StanfordCoreNLP default and
     * adding custom dependency representations (to include root elements)
     * 
     * @param anno Document to be output as XML
     * @throws IOException
     */
    public static Document stanfordToXML(StanfordCoreNLP pipeline, Annotation anno) {
        // For future versions of Stanford, we would use this:
        // Document xmlDoc = XMLOutputter.annotationToDoc(anno, pipeline);
        
        Document xmlDoc = pipeline.annotationToDoc(anno);

        Element root = xmlDoc.getRootElement();
        Element docElem = (Element) root.getChild(0);

        // rename coreference parent tag to "coreferences"
        Element corefElem = docElem.getFirstChildElement("coreference");
        // because StanfordCoreNLP.annotationToDoc() only appends the coref
        // element if it is nonempty (per Ben's request)
        if (corefElem == null) {
            Element corefInfo = new Element("coreferences", null);
            docElem.appendChild(corefInfo);
        } else {
            corefElem.setLocalName("coreferences");
        }

        if (do_deps) {
            if (debug) {
                System.err.println("Annotating dependencies");
            }

            // add dependency annotations (need to do it this way because
            // CoreNLP
            // does not include root annotation, and format is different from
            // AnnotatedGigaword)
            for (CoreMap sentence : anno.get(SentencesAnnotation.class)) {
                try {
                    ParserAnnotatorUtils.fillInParseAnnotations(false, sentence, sentence.get(TreeAnnotation.class));
                } catch (Exception e) {
                    if (debug) {
                        System.err.println("Error filling in parse annotation for sentence " + sentence);
                    }
                }

            }
            List<CoreMap> sentences = anno.get(CoreAnnotations.SentencesAnnotation.class);
            Elements sentElems = docElem.getFirstChildElement("sentences").getChildElements("sentence");
            for (int i = 0; i < sentElems.size(); i++) {
                Element thisSent = sentElems.get(i);
                thisSent.addAttribute(new Attribute("id", "" + sentences.get(i).get(SentenceIndexAnnotation.class)));
                Element basicDepElem = thisSent.getFirstChildElement("basic-dependencies");
                SemanticGraph semGraph = sentences.get(i).get(
                        SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
                addDependencyToXML(semGraph, basicDepElem);
                Element colDepElem = thisSent.getFirstChildElement("collapsed-dependencies");
                semGraph = sentences.get(i).get(SemanticGraphCoreAnnotations.CollapsedDependenciesAnnotation.class);
                addDependencyToXML(semGraph, colDepElem);
                Element colCcDepElem = thisSent.getFirstChildElement("collapsed-ccprocessed-dependencies");
                semGraph = sentences.get(i).get(
                        SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
                addDependencyToXML(semGraph, colCcDepElem);
            }
        }

        return xmlDoc;
    }

    /**
     * NOTICE: Copied from edu.jhu.annotation.GigawordAnnotator.
     * 
     * add dependency relations to the XML. adapted from StanfordCoreNLP to add
     * root dependency and change format
     * 
     * @param semGraph the dependency graph
     * @param parentElem the element to attach dependency info to
     */
    public static void addDependencyToXML(SemanticGraph semGraph, Element parentElem) {
        if (semGraph != null && semGraph.edgeCount() > 0) {
            Element rootElem = new Element("dep");
            rootElem.addAttribute(new Attribute("type", "root"));
            Element rootGovElem = new Element("governor");
            rootGovElem.appendChild("0");
            rootElem.appendChild(rootGovElem);
            // need to surround this in a try/catch in case there is no
            // root in the dependency graph
            try {
                String rootIndex = Integer.toString(semGraph.getFirstRoot().get(IndexAnnotation.class));
                Element rootDepElem = new Element("dependent");
                rootDepElem.appendChild(rootIndex);
                rootElem.appendChild(rootDepElem);
                parentElem.appendChild(rootElem);
            } catch (Exception e) {
            }
            for (SemanticGraphEdge edge : semGraph.edgeListSorted()) {
                String rel = edge.getRelation().toString();
                rel = rel.replaceAll("\\s+", "");
                int source = edge.getSource().index();
                int target = edge.getTarget().index();

                Element depElem = new Element("dep");
                depElem.addAttribute(new Attribute("type", rel));

                Element govElem = new Element("governor");
                govElem.appendChild(Integer.toString(source));
                depElem.appendChild(govElem);

                Element dependElem = new Element("dependent");
                dependElem.appendChild(Integer.toString(target));
                depElem.appendChild(dependElem);

                parentElem.appendChild(depElem);
            }
        }
    }

}
