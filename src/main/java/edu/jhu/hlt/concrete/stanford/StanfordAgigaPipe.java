package edu.jhu.hlt.concrete.stanford;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.agiga.AgigaDocument;
import edu.jhu.hlt.concrete.Concrete;
import edu.jhu.hlt.concrete.Concrete.Communication;
import edu.jhu.hlt.concrete.Concrete.Section;
import edu.jhu.hlt.concrete.Concrete.Sentence;
import edu.jhu.hlt.concrete.Concrete.UUID;
import edu.jhu.hlt.concrete.io.ProtocolBufferReader;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.trees.Tree;


public class StanfordAgigaPipe {
    static final String usage = "You must specify an input path: java edu.jhu.hlt.concrete.stanford.StanfordAgigaPipe --input path/to/inputfile\n"
	+ "  Optional arguments: \n"
	+ "       --only-tokenize t|f\n\t\ttokenize and serialize (no parsing/CoreNLP) (default: f)\n"
	+ "       --aggregate-by-first-section-number t|f\n\t\ttaggregate by lead section number (default: f)\n"
	+ "       --debug\n\t\t to print debugging messages (default: false)\n";
	
    private boolean debug = false;
    private int sentenceCount = 1; // for flat files, no document structure

    private boolean aggregateSectionsByFirst = false;
    private boolean tokenize = true;
    private boolean onlyTokenize = false;
    private boolean parse = false;

    private ProtocolBufferReader pbr;

    private String inputFile = null;
    private InMemoryAnnoPipeline pipeline;
        
    public static void main(String[] args){
	StanfordAgigaPipe sap = new StanfordAgigaPipe(args);
	sap.go();
    }

    public StanfordAgigaPipe(String[] args) {
	parseArgs(args);
	if(inputFile == null){
	    System.err.println(usage);
	    System.exit(1);
	}
	try {
	    pbr = new ProtocolBufferReader(inputFile, Concrete.Communication.class);
	} catch(Exception e){
	    System.err.println("Trouble reading in protobuf file " + inputFile);
	    System.err.println(e.getMessage());
	    System.exit(1);
	}
	pipeline = new InMemoryAnnoPipeline(onlyTokenize);
    }

    public void parseArgs(String[] args){
	int i = 0;
    	try {
	    while (i < args.length) {
		if(args[i].equals("--only-tokenize"))
		    onlyTokenize = args[++i].equalsIgnoreCase("t");
		if(args[i].equals("--aggregate-by-first-section-number"))
		    aggregateSectionsByFirst = args[++i].equals("t");
		else if (args[i].equals("--debug")) debug = true;
		else if (args[i].equals("--input")) inputFile = args[++i];
		else{
		    System.err.println("Invalid option: " + args[i]);
		    System.err.println(usage);
		    System.exit(1);
		} 
		i++;
	    }
	} catch (Exception e) {
	    System.err.println(usage);
	    System.exit(1);
	}
    }

    public void go(){
	int num_communications_processed=0;
	while(pbr.hasNext()){
	    Communication comm = (Communication)(pbr.next());
	    RunPipelineOnCommunicationSectionsAndSentences(comm);
	    num_communications_processed++;
	}
    }

    public AgigaDocument annotate(Annotation annotation) {
        System.out.println("ANNOTATING: " + annotation);
        try {
            return pipeline.annotate(annotation);
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * WARNING: This has the side effects of clearing sectionUUIDs and sectionBuffer.
     * These two clears are imperative to this working correctly.
     */
    public Communication process(Communication commToAnnotate,
				 Annotation annotation, UUID sectionSegmentationUUID,
				 List<UUID> sentenceSegmentationUUIDs,
				 List<UUID> sectionUUIDs, StringBuilder sectionBuffer){
	AgigaDocument agigaDoc = annotate(annotation);
	//NOTE: The *actual* call needs to incorporate sentenceSegmentationUUIDs
	AgigaConcreteAnnotator t = new AgigaConcreteAnnotator();
	System.out.println(sectionUUIDs);
	System.out.println(sentenceSegmentationUUIDs);
	Communication newcomm = t.annotate(commToAnnotate, 
					   sectionSegmentationUUID, 
					   sectionUUIDs,
					   sentenceSegmentationUUIDs,	// TODO
					   agigaDoc);	
	//FINALLY: clear the  lists
	sectionBuffer = new StringBuilder();
	sectionUUIDs.clear();
	sentenceSegmentationUUIDs.clear();
	return newcomm;
    }
	
    private void RunPipelineOnCommunicationSectionsAndSentences(Communication comm) {
	if (!comm.hasText())
	    throw new IllegalArgumentException("Expecting Communication Text.");
	if (comm.getSectionSegmentationCount() == 0)
	    throw new IllegalArgumentException("Expecting Communication SectionSegmentations.");
		
	Communication annotatedCommunication = comm;
	
	String commText = comm.getText();
	List<Annotation> finishedAnnotations = new ArrayList<Annotation>();

	int prevSectionNumber = -1;
	int currSectionNumber = -1;
	StringBuilder sectionBuffer = new StringBuilder();

	//TODO: get section and sentence segmentation info from metadata
	List<Section> sections = comm.getSectionSegmentationList().get(0).getSectionList();
	List<UUID> sectionUUIDs = new ArrayList<UUID>();
	List<UUID> sentenceSegmentationUUIDs = new ArrayList<UUID>();
	UUID sectionSegmentationUUID = comm.getSectionSegmentation(0).getUuid();
	for (Section section : sections) {
	    if ((section.hasKind() && section.getKind() != Section.Kind.PASSAGE) 
		|| section.getSentenceSegmentationCount() == 0)
		continue;
	    //currently, we'll only make this smart enough to 
	    //group by lead section number
	    if(section.getNumberCount() > 0) {
		currSectionNumber = section.getNumber(0);
	    }
	    if(currSectionNumber!=prevSectionNumber ||
	       (aggregateSectionsByFirst && 
		section.getNumberCount()== 0 && 
		sectionBuffer.length() > 0)){
		//process previous section-aggregate
		annotatedCommunication = process(annotatedCommunication,
						 new Annotation(sectionBuffer.toString()),
						 sectionSegmentationUUID,
						 sectionUUIDs,
						 sentenceSegmentationUUIDs,
						 sectionBuffer);
	    }
	    sectionUUIDs.add(section.getUuid());
	    List<Sentence> concreteSentences = section
		.getSentenceSegmentationList().get(0).getSentenceList();	    
	    sentenceSegmentationUUIDs.add(section.getSentenceSegmentation(0).getUuid());
	    for (Sentence sentence : concreteSentences) {	
		if (!sentence.hasTextSpan())
		    throw new IllegalArgumentException("Expecting TextSpan from Communication Sentence.");			
		String sText = commText.substring(sentence.getTextSpan().getStart(), 
						  sentence.getTextSpan().getEnd());
		if(sText!=null) sectionBuffer.append(sText);
	    }
	    if(section.getNumberCount() > 0)
		prevSectionNumber = currSectionNumber;
	}
	if(sectionBuffer.length() > 0){	
	    annotatedCommunication = process(annotatedCommunication,
					     new Annotation(sectionBuffer.toString()),
					     sectionSegmentationUUID,
					     sectionUUIDs,
					     sentenceSegmentationUUIDs,
					     sectionBuffer);
	}
    }
	
    private List<Communication> readInputCommunications(String path) {
        List<Communication> communications = new ArrayList<Communication>();
		
        try {
	    BufferedInputStream input = new BufferedInputStream(new FileInputStream(path));
	        
	    while (input.available() != 0) {
		communications.add(Communication.parseDelimitedFrom(input));
	    }
			
	    input.close();
	} catch (Exception e) {
	    e.printStackTrace();
	}
		
	return communications;
    }
	
    private void writeOutputCommunications(String path, List<Communication> outputCommunications) {
	try {
	    BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(path));
			
	    for (Communication comm : outputCommunications)
		comm.writeDelimitedTo(output);
				
	    output.close();
	} catch (Exception e) {
	    e.printStackTrace();
	}
	
    }
	
    /* This method contains code for transforming lists of Stanford's CoreLabels into 
     * Concrete tokenizations.  We might want to use it if we get rid of agiga. 
     */
    // private Tokenization coreLabelsToTokenization(List<CoreLabel> coreLabels) {		
    // 	List<Token> tokens = new ArrayList<Token>();
    // 	List<TaggedToken> lemmas = new ArrayList<TaggedToken>();
    // 	List<TaggedToken> nerTags = new ArrayList<TaggedToken>();
    // 	List<TaggedToken> posTags = new ArrayList<TaggedToken>();
		
    // 	int tokenId = 0;
    // 	for (CoreLabel coreLabel : coreLabels) {
    // 	    if (coreLabel.lemma() != null) {
    // 		lemmas.add(
    // 			   TaggedToken.newBuilder()
    // 			   .setTag(coreLabel.lemma())
    // 			   .setTokenId(tokenId)
    // 			   .build()
    // 			   );
    // 	    }
			
    // 	    if (coreLabel.ner() != null) {
    // 		nerTags.add(
    // 			    TaggedToken.newBuilder()
    // 			    .setTag(coreLabel.ner())
    // 			    .setTokenId(tokenId)
    // 			    .build()
    // 			    );					
    // 	    }
			
    // 	    if (coreLabel.tag() != null) {
    // 		posTags.add(
    // 			    TaggedToken.newBuilder()
    // 			    .setTag(coreLabel.tag())
    // 			    .setTokenId(tokenId)
    // 			    .build()
    // 			    );						
    // 	    }
			
    // 	    tokens.add(
    // 		       Token.newBuilder()
    // 		       .setTokenId(tokenId)
    // 		       .setTextSpan(
    // 				    TextSpan.newBuilder()
    // 				    .setStart(coreLabel.beginPosition())
    // 				    .setEnd(coreLabel.endPosition())
    // 				    .build()
    // 				    )
    // 		       .setText(coreLabel.value())
    // 		       .build()
    // 		       );
			
    // 	    tokenId++;
    // 	}
		
    // 	Tokenization tokenization = Tokenization.newBuilder()
    // 	    .setUuid(IdUtil.generateUUID())
    // 	    .setKind(Kind.TOKEN_LIST)
    // 	    .addPosTags(
    // 			TokenTagging.newBuilder()
    // 			.setUuid(IdUtil.generateUUID())
    // 			.addAllTaggedToken(posTags)
    // 			.build()
    // 			)
    // 	    .addNerTags(
    // 			TokenTagging.newBuilder()
    // 			.setUuid(IdUtil.generateUUID())
    // 			.addAllTaggedToken(nerTags)
    // 			.build()
    // 			)
    // 	    .addLemmas(
    // 		       TokenTagging.newBuilder()
    // 		       .setUuid(IdUtil.generateUUID())
    // 		       .addAllTaggedToken(lemmas)
    // 		       .build()
    // 		       )
    // 	    .addAllToken(tokens)
    // 	    .build();
		
    // 	return tokenization;
    // }

        /**
     * convert a tree t to its token representation
     * 
     * @param t
     * @return
     * @throws IOException
     */
    protected String getText(Tree t) throws IOException {
	StringBuffer sb = new StringBuffer();
	if (t == null) {
	    return null;
	}
	for (Tree tt : t.getLeaves()) {
	    sb.append(tt.value());
	    sb.append(" ");
	}
	return sb.toString().trim();
    }
}
