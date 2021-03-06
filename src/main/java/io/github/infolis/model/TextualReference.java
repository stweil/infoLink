package io.github.infolis.model;

import io.github.infolis.util.SerializationUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Function;
import com.google.common.collect.Lists;

/**
 * Class for saving contexts (= surrounding words of a term).
 *
 * @author kata
 * @author kba
 *
 */
@XmlRootElement(name = "context")
@XmlAccessorType(XmlAccessType.FIELD)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextualReference extends BaseModel {

	@XmlTransient
	private List<String>	leftWords;
	@XmlTransient
	private List<String>	rightWords;
	@XmlElement(name = "leftContext")
	private String			leftText;
	@XmlElement(name = "rightContext")
	private String			rightText;
	@XmlAttribute
	private String			reference;
	@XmlAttribute
	private String			textFile;
	@XmlTransient
	private String			pattern;
	private String			mentionsReference;


    public TextualReference() {
    }

    public TextualReference(String term) {
        this.leftText = "";
        this.rightText = "";
        this.leftWords = new ArrayList<>();
        this.rightWords = new ArrayList<>();
        this.textFile = "";
        this.pattern = "";
        this.mentionsReference = "";
    }

    public TextualReference(String leftText, String reference, String rightText, String textFile, String pattern, String mentionsReference) {
        setLeftText(leftText);
        setRightText(rightText);
        this.reference = reference;
        this.textFile = textFile;
        this.pattern = pattern;
        this.mentionsReference = mentionsReference;
    }

	public String toXML() {
		return "\t<context reference=\"" + SerializationUtils.escapeXML(this.getReference()) + "\" textFile=\""
				+ this.getFile() + "\">" + System.getProperty("line.separator") + "\t\t"
				+ "<leftContext>" + this.getLeftText() + "</leftContext>"
				+ System.getProperty("line.separator") + "\t\t" + "<rightContext>"
				+ this.getRightText() + "</rightContext>" + System.getProperty("line.separator")
                                + "<mentionsReference>" + this.getMentionsReference() + "</mentionsReference>" + System.getProperty("line.separator")
				+ "\t</context>" + System.getProperty("line.separator");
	}

	@Override
	public String toString() {
		return this.getLeftText() + " " + this.getReference() + " " + this.getRightText();
	}

	@JsonIgnore
	public String getContextWithoutTerm() {
		return this.getLeftText() + " " + this.getRightText();
	}

	public List<String> getLeftWords() {
		return leftWords;
	}

	public void setLeftWords(List<String> leftWords) {
		this.leftWords = leftWords;
	}

	public List<String> getRightWords() {
		return rightWords;
	}

	public void setRightWords(List<String> rightWords) {
		this.rightWords = rightWords;
	}

	public String getLeftText() {
		return leftText;
	}

	public void setLeftText(String leftText) {
		this.leftText = leftText;
                setLeftWords(Arrays.asList(leftText.split("\\s+")));
	}

	public String getRightText() {
		return rightText;
	}

	public void setRightText(String rightText) {
		this.rightText = rightText;
                setRightWords(Arrays.asList(rightText.split("\\s+")));
	}

	public String getReference() {
		return reference;
	}

	public void setReference(String reference) {
		this.reference = reference;
	}

	public String getFile() {
		return textFile;
	}

	public void setFile(String file) {
		this.textFile = file;
	}

	public String getPattern() {
		return pattern;
	}

	public void setPattern(String patternUri) {
		this.pattern = patternUri;
	}

	public static List<String> getContextStrings(List<TextualReference> contexts) {
		Function<TextualReference, String> context_toString = new Function<TextualReference, String>() {
			public String apply(TextualReference c) {
				return c.toString();
			}
		};
		return new ArrayList<String>(Lists.transform(contexts, context_toString));
	}

	public String toPrettyString() {
		StringBuilder sb = new StringBuilder();
		return sb.append(leftText)
			.append("**[  ")
			.append(reference)
			.append("  ]**")
			.append(rightText)
			.toString();
	}

    /**
     * @return the mentionsReference
     */
    public String getMentionsReference() {
        return mentionsReference;
    }

    /**
     * @param mentionsReference the mentionsReference to set
     */
    public void setMentionsReference(String mentionsReference) {
        this.mentionsReference = mentionsReference;
    }

}
