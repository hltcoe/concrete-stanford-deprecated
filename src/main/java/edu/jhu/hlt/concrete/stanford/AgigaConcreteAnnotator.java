package edu.jhu.hlt.concrete.stanford;

import edu.jhu.hlt.concrete.Concrete;
import edu.jhu.hlt.concrete.Concrete.*;
import edu.jhu.hlt.concrete.util.*;
import edu.jhu.hlt.concrete.agiga.AgigaConverter;
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
class AgigaConcreteAnnotator {

	private long timestamp;
	private AnnotationMetadata metadata() {
		return AnnotationMetadata.newBuilder()
			.setTool("concrete-stanford")
			.setTimestamp(timestamp)
			.build();
	}
		
	private Communication comm;
	private UUID sectionSegmentationId;
	private List<UUID> sectionIds;
	private List<UUID> sentenceSegIds;
	private AgigaDocument agigaDoc;
	private int agigaSentPtr = -1;
	private int sectionPtr = -1;

	// need to reference this in building corefs
	private List<Tokenization> tokenizations;

	public static String uuidStr(UUID id) {
		return id == null ? "null" : new java.util.UUID(id.getHigh(), id.getLow()).toString();
	}

	public synchronized Communication annotate(
			Communication comm,
			UUID sectionSegmentationId,
			List<UUID> sectionIds,		// relevant sections (look inside for #sentences)
			List<UUID> sentenceSegIds,	// ids of the sentence splits to use for each section
			AgigaDocument agigaDoc) {

		if(sectionIds.size() != sentenceSegIds.size()) {
			throw new IllegalArgumentException(
				"sectionIds and sentenceSegIds need to have a 1-to-1 correspondence");
		}
		if(sectionIds.size() == 0) {
			System.err.println("WARNING: calling annotate with no sections specified!");
			return comm.toBuilder().build();
		}

		System.out.println("[AgigaConcreteAnnotator debug]");
		System.out.println("sectionSegmentationId = " + uuidStr(sectionSegmentationId));
		for(int i=0; i<sectionIds.size(); i++) {
			System.out.printf("sectionId(%d)=%s sentenceSegIds(%d)=%s\n",
				i, uuidStr(sectionIds.get(i)), i, uuidStr(sentenceSegIds.get(i)));
		}

		this.timestamp = Calendar.getInstance().getTimeInMillis() / 1000;
		this.sectionSegmentationId = sectionSegmentationId;
		this.sectionIds = sectionIds;
		this.sentenceSegIds = sentenceSegIds;
		this.agigaDoc = agigaDoc;
		this.agigaSentPtr = 0;
		this.sectionPtr = 0;
		this.tokenizations = new ArrayList<Tokenization>();
		return f1(comm);
	}
	
	// Communication
	// SectionSegmentation
	// Section
	// SentenceSegmentation
	// Sentence
	// Tokenization

	// replace relavant SectionSegmentation
	private Communication f1(Communication in) {

		Communication.Builder newComm = in.toBuilder();
		SectionSegmentation newSS = null;
		int remove = -1;
		int n = newComm.getSectionSegmentationCount();
		for(int i=0; i<n; i++) {
			SectionSegmentation ss = newComm.getSectionSegmentation(i);
			if(ss.getUuid().equals(this.sectionSegmentationId)) {
				remove = i;
				newSS = f2(ss);
			}
		}
		if(remove >= 0) {
			newComm.removeSectionSegmentation(remove);
			newComm.addSectionSegmentation(newSS);
		}
		else throw new RuntimeException("couldn't find SectionSegmentation with UUID=" + this.sectionSegmentationId);

		////////////////////////////////////////////////////////////////////////////
		// corefs
		if(this.tokenizations.size() != this.agigaDoc.getSents().size()) {
			throw new RuntimeException("#agigaSents=" + agigaDoc.getSents().size() + ", #tokenizations=" + tokenizations.size());
		}
        EntityMentionSet.Builder emsb = EntityMentionSet.newBuilder()
            .setUuid(IdUtil.generateUUID())
            .setMetadata(metadata());
        EntitySet.Builder esb = EntitySet.newBuilder()
            .setUuid(IdUtil.generateUUID())
            .setMetadata(metadata());
        for(AgigaCoref coref : this.agigaDoc.getCorefs()) {
            Entity e = AgigaConverter.convertCoref(emsb, coref, this.agigaDoc, this.tokenizations);
            esb.addEntity(e);
        }
        newComm.addEntityMentionSet(emsb);
        newComm.addEntitySet(esb);

		return newComm.build();
	}

	// replace relevant Sections
	private SectionSegmentation f2(SectionSegmentation in) {

		// make empty SS
		SectionSegmentation.Builder newSS = in.toBuilder();
		int n = newSS.getSectionCount();
		assert n > 0 : "n="+n;
		for(int i=n; i>0; i--)
			newSS.removeSection(i-1);

		// add them from source
		UUID target = this.sectionIds.get(this.sectionPtr);
		System.out.println("[f2] target=" + uuidStr(target));
		for(Section section : in.getSectionList()) {
			System.out.printf("sectionPtr=%d sect.uuid=%s\n", sectionPtr, uuidStr(section.getUuid()));
			if(section.getUuid().equals(target)) {
				newSS.addSection(f3(section));
				this.sectionPtr++;
				target = this.sectionPtr < this.sectionIds.size()
					? this.sectionIds.get(this.sectionPtr)
					: null;
				System.out.println("[f2] target=" + uuidStr(target));
			}
			else newSS.addSection(section);
		}
		if(this.sectionPtr != this.sectionIds.size())
			throw new RuntimeException(String.format("found %d of %d sections", this.sectionPtr, this.sectionIds.size()));

		return newSS.build();
	}

	// replace relevant SentenceSegmentation
	private Section f3(Section in) {
		System.out.println("f3");

		// make empty Section
		Section.Builder newS = in.toBuilder();
		for(int i=in.getSentenceSegmentationCount(); i>0; i--)
			newS.removeSentenceSegmentation(i-1);

		// add back to it
		UUID target = this.sentenceSegIds.get(this.sectionPtr);	// GLOBAL STATE
		int found = 0;
		for(SentenceSegmentation ss : in.getSentenceSegmentationList()) {
			if(ss.getUuid().equals(target)) {
				newS.addSentenceSegmentation(f4(ss));
				found++;
			}
			else newS.addSentenceSegmentation(ss);
		}
		if(found != 1)
			throw new RuntimeException("expected 1 sentence segmentation, found " + found);

		return newS.build();
	}

	// replace all Sentences
	private SentenceSegmentation f4(SentenceSegmentation in) {
		System.out.println("f4");

		// make empty SentenceSegmentation
		SentenceSegmentation.Builder newSS = in.toBuilder();
		int n = in.getSentenceCount();
		assert n > 0 : "n=" + n;
		for(int i=n; i>0; i--)
			newSS.removeSentence(i-1);

		// add back to it
		for(Sentence s : in.getSentenceList())
			newSS.addSentence(f5(s));

		return newSS.build();
	}

	// add a Tokenization
	private Sentence f5(Sentence in) {
		System.out.println("f5");
		AgigaSentence asent = this.agigaDoc.getSents().get(agigaSentPtr++);
		Tokenization tok = AgigaConverter.convertTokenization(asent);	// tokenization has all the annotations
		this.tokenizations.add(tok);
		Sentence.Builder newS = in.toBuilder();
		newS.addTokenization(tok);
		return newS.build();
	}
}

