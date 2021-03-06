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
import nu.xom.Node;
import nu.xom.Serializer;
import edu.jhu.agiga.AgigaDocument;
import edu.jhu.agiga.AgigaPrefs;
import edu.jhu.agiga.BytesAgigaDocumentReader;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreAnnotations.IndexAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentenceIndexAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.Annotator;
import edu.stanford.nlp.pipeline.ParserAnnotatorUtils;
import edu.stanford.nlp.pipeline.POSTaggerAnnotator;
import edu.stanford.nlp.pipeline.PTBTokenizerAnnotator;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.pipeline.WordsToSentencesAnnotator;
import edu.stanford.nlp.trees.semgraph.SemanticGraph;
import edu.stanford.nlp.trees.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.trees.TreeCoreAnnotations.TreeAnnotation;
import edu.stanford.nlp.trees.semgraph.SemanticGraph;
import edu.stanford.nlp.trees.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.trees.semgraph.SemanticGraphCoreAnnotations.CollapsedDependenciesAnnotation;

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
    // Document counter.
    private static int docCounter;

    private PTBTokenizerAnnotator ptbTokenizer;
    //private POSTaggerAnnotator posTagger;
    private WordsToSentencesAnnotator words2SentencesAnnotator;
    //NOTE: we're only using this for its annotationToDoc method
    private StanfordCoreNLP pipeline;

    //    private static String[] sentenceLevelStages = {"pos", "lemma", "parse"};
    private static String[] documentLevelStages = {"pos", "lemma", "parse", "ner", "dcoref"};


    public InMemoryAnnoPipeline(boolean onlyTokenize) {
        docCounter = 0;

        ptbTokenizer = new PTBTokenizerAnnotator();
	//posTagger = new POSTaggerAnnotator();
	words2SentencesAnnotator = new WordsToSentencesAnnotator();
	words2SentencesAnnotator.setOneSentence(true);

        Properties props = new Properties();
        String annotatorList = "tokenize, ssplit";
        if (!onlyTokenize)
            annotatorList += ", pos, lemma, parse, ner, dcoref";
        if (debug) {
            System.err.println("Using annotators " + annotatorList);
        }
        props.put("annotators", annotatorList);
        pipeline = new StanfordCoreNLP(props);
    }

    // tokenize and "split" 
    public Annotation annotateSentence(String text){
	Annotation sentence = new Annotation(text);
	ptbTokenizer.annotate(sentence);
	words2SentencesAnnotator.annotate(sentence);
	return sentence;
    }
    
    public AgigaDocument annotate(Annotation annotation) throws IOException {
        return annotate(pipeline, annotation);
    }
    
    public static AgigaDocument annotate(StanfordCoreNLP pipeline, Annotation annotation) throws IOException {
	for(String stage : documentLevelStages){
	    if(stage.equals("dcoref")){
		fixNullDependencyGraphs(annotation);
	    }
	    try{
		(StanfordCoreNLP.getExistingAnnotator(stage)).annotate(annotation);
	    } catch(Exception e){
		System.err.println("Error annotating " + stage);
	    }
	}
	
        // Convert to an XML document.
        Document xmlDoc = stanfordToXML(pipeline, annotation);
        
        // Convert the XML document to an AgigaDocument.
        AgigaDocument agigaDoc = xmlToAgigaDoc(xmlDoc);
	if(debug){
	    System.err.println("agigaDoc has " + agigaDoc.getSents().size() + " sentences");
	    System.err.println("annotation has " + annotation.get(SentencesAnnotation.class).size());
	    System.err.println("annotation has " + annotation.get(SentencesAnnotation.class));
	}
        return agigaDoc;
    }

    /**
     * sentences with no dependency structure have null values for the various
     * dependency annotations. make sure these are empty dependencies instead
     * to prevent coref-resolution from dying
     **/
    public static void fixNullDependencyGraphs(Annotation anno) {
        for (CoreMap sent : anno.get(SentencesAnnotation.class)) {
            if (sent.get(CollapsedDependenciesAnnotation.class) == null) {
                sent.set(CollapsedDependenciesAnnotation.class, new SemanticGraph());
            }
	}
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

        if (debug) {
            System.out.println(baos.toString("UTF-8"));
        }
        
        AgigaPrefs agigaPrefs = new AgigaPrefs();
        agigaPrefs.setAll(true);
        BytesAgigaDocumentReader adr = new BytesAgigaDocumentReader(baos.toByteArray(), agigaPrefs);
        if (!adr.hasNext()) {
            throw new IllegalStateException("No documents found.");
        }
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
        
        // The document element will be a <document/> tag, but must be a <DOC/>.
        docElem.setLocalName("DOC");
        
        // Add empty id and type attributes to the <DOC>.
        docElem.addAttribute(new Attribute("id", Integer.toString(docCounter++)));
        docElem.addAttribute(new Attribute("type", "NONE"));
        
        // Add an empty id attribute to each sentence. 
        Elements sents = docElem.getFirstChildElement("sentences").getChildElements("sentence");
        for (int i = 0; i < sents.size(); i++) {
            Element thisSent = sents.get(i);
            thisSent.addAttribute(new Attribute("id", Integer.toString(i)));
        }
        
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
                Element basicDepElem = thisSent.getFirstChildElement("basic-dependencies");
                basicDepElem.removeChildren();
                SemanticGraph semGraph = sentences.get(i).get(
                        SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
                addDependencyToXML(semGraph, basicDepElem);
                
                Element colDepElem = thisSent.getFirstChildElement("collapsed-dependencies");
                colDepElem.removeChildren();
                semGraph = sentences.get(i).get(SemanticGraphCoreAnnotations.CollapsedDependenciesAnnotation.class);
                addDependencyToXML(semGraph, colDepElem);
                
                Element colCcDepElem = thisSent.getFirstChildElement("collapsed-ccprocessed-dependencies");
                colCcDepElem.removeChildren();
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

    // return various annotators from the CoreNLP tools
    public Annotator nerAnnotator() {
	return StanfordCoreNLP.getExistingAnnotator("ner");
    }

    public Annotator dcorefAnnotator() {
	return StanfordCoreNLP.getExistingAnnotator("dcoref");
    }

}
