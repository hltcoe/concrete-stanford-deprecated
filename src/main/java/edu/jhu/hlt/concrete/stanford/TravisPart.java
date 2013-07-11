package edu.jhu.hlt.concrete.stanford;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

import edu.jhu.agiga.AgigaCoref;
import edu.jhu.agiga.AgigaDocument;
import edu.jhu.agiga.AgigaSentence;
import edu.jhu.hlt.concrete.Concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Concrete.Communication;
import edu.jhu.hlt.concrete.Concrete.Entity;
import edu.jhu.hlt.concrete.Concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Concrete.EntitySet;
import edu.jhu.hlt.concrete.Concrete.Section;
import edu.jhu.hlt.concrete.Concrete.SectionSegmentation;
import edu.jhu.hlt.concrete.Concrete.Sentence;
import edu.jhu.hlt.concrete.Concrete.SentenceSegmentation;
import edu.jhu.hlt.concrete.Concrete.Tokenization;
import edu.jhu.hlt.concrete.Concrete.UUID;
import edu.jhu.hlt.concrete.agiga.AgigaConverter;
import edu.jhu.hlt.concrete.util.IdUtil;

/**
 * given a Communication (with Sections and Sentences added)
 * and Stanford's annotations via an AgigaDocument,
 * add these annotations and return a new Communication
 */
public class TravisPart {

	public static final long timestamp = Calendar.getInstance().getTimeInMillis() / 1000;
	public static final AnnotationMetadata metadata =
		AnnotationMetadata.newBuilder()
			.setTool("concrete-stanford")
			.setTimestamp(timestamp)
			.build();

	public static Communication annotateCommunication(
			Communication comm,
			UUID sectionSegmentationId,
			List<UUID> sectionIds,		// relevant sections (look inside for #sentences)
			AgigaDocument agigaDoc) {

		////////////////////////////////////////////////////////////////////////////
		Communication.Builder newComm = comm.toBuilder();
		SectionSegmentation.Builder newSS = null;
		
		int numSectionSegmentations = comm.getSectionSegmentationCount();
		int sectionSegmentationToReplace = -1; 
		for (int i = 0; i < numSectionSegmentations; i++) {
		    SectionSegmentation ss = comm.getSectionSegmentation(i);
		    if (ss.getUuid().equals(sectionSegmentationId)) {
                        newSS = ss.toBuilder();
                        sectionSegmentationToReplace = i;
                        break;
                    }
		}
		
		if(newSS == null || sectionSegmentationToReplace == -1)
			throw new RuntimeException("couldn't find SectionSegmentation with UUID=" + sectionSegmentationId);

		List<Tokenization> tokenizationsAdded = new ArrayList<>();
		////////////////////////////////////////////////////////////////////////////
		// sections (and below)
		int agigaSentPtr = 0;
		
		
		for(UUID targetSectionUUID : sectionIds) {
		    int sectionIndexToReplace = -1;
			Section sectionToTokenize = null;
			List<Section> sections = newSS.getSectionList();
			
			for (int i = 0; i < newSS.getSectionCount(); i++) {
			    Section currSection = sections.get(i);
			    if (currSection.getUuid().equals(targetSectionUUID)) {
			        sectionToTokenize = currSection;
			        // replace the i-th section when we re-construct the protobuf object. 
			        sectionIndexToReplace = i;
			    }
			}
			
			if (sectionToTokenize == null || sectionIndexToReplace == -1)
			    throw new IllegalArgumentException("Did not find the target UUID [" + 
			            IdUtil.uuidToString(targetSectionUUID) + "] in the sections of SectionSegmentation " + 
			            sectionSegmentationToReplace + ".");

			// match up Concrete.Sentences with agiga sentences in this section
			
			// This appears to be adding a single tokenization to a particular sentence, then
			// adding that to a new SectionSegmentation, then adding *that* back to the big concrete object. 
			// If I'm mistaken, the following changes aren't appropriate. 
			
			// Assumes one sentence segmentation. 
			// If more than one sentence segmentation is coming in, 
			// need to loop over them and add tokenizations to each.
			SentenceSegmentation.Builder sentSeg = sectionToTokenize
			        .getSentenceSegmentation(0)
			        .toBuilder();

			for (int k = 0; k < sentSeg.getSentenceCount(); k++) {
			    AgigaSentence asent = agigaDoc.getSents().get(agigaSentPtr++);
			    Tokenization tok = AgigaConverter.convertTokenization(asent);	// tokenization has all the annotations
			    tokenizationsAdded.add(tok);
			    // Add the tokenization to the sentence. 
			    sentSeg.getSentenceBuilder(k).addTokenization(tok);
			}
                        
                        // We now have the original sentences with the additional tokenizations.
                        // We want to replace the section's original SentenceSegmentation
			// with this one. 
			Section newSecton = sectionToTokenize.toBuilder()
			    .removeSentenceSegmentation(0)
			    .addSentenceSegmentation(sentSeg.build())
			    .build();
			
			// We have now replaced the Section's information.
			// Update the original communication with this information. 
			SectionSegmentation newSectSeg = newComm
			    .getSectionSegmentationBuilder(sectionSegmentationToReplace)
			    .removeSection(sectionIndexToReplace)
			    .addSection(sectionIndexToReplace, newSecton)
			    .setMetadata(metadata)
			    .build();
			
			newComm.addSectionSegmentation(newSectSeg);    
		}
		
		////////////////////////////////////////////////////////////////////////////
		// corefs
                EntityMentionSet.Builder emsb = EntityMentionSet.newBuilder()
                    .setUuid(IdUtil.generateUUID())
                    .setMetadata(metadata);
                EntitySet.Builder esb = EntitySet.newBuilder()
                    .setUuid(IdUtil.generateUUID())
                    .setMetadata(metadata);
                for(AgigaCoref coref : agigaDoc.getCorefs()) {
                    Entity e = AgigaConverter.convertCoref(emsb, coref, agigaDoc, tokenizationsAdded);
                    esb.addEntity(e);
                }
                newComm.addEntityMentionSet(emsb);
                newComm.addEntitySet(esb);

		////////////////////////////////////////////////////////////////////////////
		return newComm.build();
	}
}

