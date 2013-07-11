package edu.jhu.hlt.concrete.stanford;

import edu.jhu.hlt.concrete.Concrete;
import edu.jhu.hlt.concrete.Concrete.*;
import edu.jhu.hlt.concrete.util.*;
import edu.jhu.agiga.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

/**
 * given a Communication (with Sections and Sentences added)
 * and Stanford's annotations via an AgigaDocument,
 * add these annotations and return a new Communication
 */
class TravisPart {

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
		for(SectionSegmentation ss : newComm.getSectionSegmentationList()) {
			if(ss.getUuid().equals(sectionSegmentationId)) {
				newSS = ss.toBuilder();
				break;
			}
		}
		if(newSS == null)
			throw new RuntimeException("couldn't find SectionSegmentation with UUID=" + sectionSegmentationId);

		////////////////////////////////////////////////////////////////////////////
		// sections (and below)
		List<Tokenization> toks = new ArrayList<Tokenization>();
		Iterator<Section> existingSections = newSS.getSectionList().iterator();
		Section curSection = existingSections.next();
		int agigaSentPtr = 0;
		for(UUID targetSectionUUID : sectionIds) {			
			// find the section
			while(!curSection.getUuid().equals(targetSectionUUID)) {
				newSS.addSection(curSection);
				curSection = existingSections.next();
			}

			// match up Concrete.Sentences with agiga sentences in this section
			Section newSection = curSection.toBuilder();
			for(Sentence sent : curSect.getSectionList()) {
				AgigaSentence asent = agigaDoc.getSents().get(agigaSentPtr++);
				Tokenization tok = AgigaConverter.convertTokenization(asent);	// tokenization has all the annotations
				toks.add(tok);	// store tokenizations for later (mentions, TokenRefSeqs need Tokenizations UUIDs)
				Sentence newSentence = sent.toBuilder();
				newSentence.addTokenization(tok);
				newSection.addSentence(newSentence);
			}
			newSS.addSection(newSection);
		}
		newSS.setMetadata(metadata());
		newComm.addSectionSegmentation(newSS);

		////////////////////////////////////////////////////////////////////////////
		// corefs
        EntityMentionSet.Builder emsb = EntityMentionSet.newBuilder()
            .setUuid(IdUtil.generateUUID())
            .setMetadata(metadata());
        EntitySet.Builder esb = EntitySet.newBuilder()
            .setUuid(IdUtil.generateUUID())
            .setMetadata(metadata());
        for(AgigaCoref coref : agigaDoc.getCorefs()) {
            Entity e = AgigaConverter.convertCoref(emsb, coref, agigaDoc, toks);
            esb.addEntity(e);
        }
        newComm.addEntityMentionSet(emsb);
        newComm.addEntitySet(esb);

		////////////////////////////////////////////////////////////////////////////
		return newComm.build();
	}
}

