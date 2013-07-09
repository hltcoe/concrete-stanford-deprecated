package edu.jhu.hlt.concrete.stanford;

import java.util.List;
import edu.jhu.hlt.concrete.Concrete.*;
import edu.jhu.hlt.concrete.Concrete.Sentence;

public class StanfordAgigaPipe {
	
	public StanfordAgigaPipe() {
	
	}
	
	/* TODO: Put this where it makes sense */
	private void StepThroughCommunicationSectionsAndSentences(Communication comm) {
		if (!comm.hasText())
			throw new IllegalArgumentException("Expecting Communication Text.");
		if (comm.getSectionSegmentationCount() == 0)
			throw new IllegalArgumentException("Expecting Communication SectionSegmentations.");
		
		String commText = comm.getText();
		
		List<Section> sections = comm.getSectionSegmentationList().get(0).getSectionList();
		for (Section section : sections) {
			if ((section.hasKind() && section.getKind() != Section.Kind.PASSAGE) 
				|| section.getSentenceSegmentationCount() == 0)
				continue;
			
			List<Sentence> sentences = section.getSentenceSegmentationList().get(0).getSentenceList();
			for (Sentence sentence : sentences) {	
				if (!sentence.hasTextSpan())
					throw new IllegalArgumentException("Expecting TextSpan from Communication Sentence.");
			
				String sText = commText.substring(sentence.getTextSpan().getStart(), sentence.getTextSpan().getEnd());
		
				/* TODO: Put in some neat things right here */
			}
		}	
	}
}
