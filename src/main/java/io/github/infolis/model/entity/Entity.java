package io.github.infolis.model.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.infolis.infolink.patternLearner.Reliability;
import io.github.infolis.model.BaseModel;
import io.github.infolis.model.TextualReference;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.queryParser.ParseException;
import org.slf4j.LoggerFactory;

/**
 * Class for all InFoLiS entities, e.g. patterns, datasets, publications.
 *
 * @author kata
 *
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@XmlRootElement(name = "Entity")
@XmlAccessorType(XmlAccessType.FIELD)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Entity extends BaseModel {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(Entity.class);

    //TODO: list of names instead one?
    @XmlAttribute
    private String name;
    private String identifier;
    private Set<String> tags;
  //TODO use uris instead of TextualReference objects (hard to change it)
    //private Collection<String> textualReferences;
    private Collection<TextualReference> textualReferences;
    private String file;

    @XmlAttribute
    private String number;
    private Map<String, Double> associations = new HashMap<>();
    private double reliability;
    private List<String> alternativeNames = new ArrayList<>();

    public Entity(String name) {
        this.name = name;
    }

    public Entity() {}

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }
    //TODO use uris instead of TextualReference objects
    /*
    public void setTextualReferences(Collection<String> uris) {
    	this.textualReferences = uris;
    }

    public Collection<String> getTextualReferences() {
    	return this.textualReferences;
    }*/

    public void setTextualReferences(Collection<TextualReference> textualReferences) {
    	this.textualReferences = textualReferences;
    }

    public Collection<TextualReference> getTextualReferences() {
    	return this.textualReferences;
    }

    /**
     * @return the tags
     */
    public Set<String> getTags() {
        return tags;
    }

    /**
     * @param tags the tags to set
     */
    public void setTags(Set<String> tags) {
        this.tags = tags;
    }

    /**
     * @param tag the tag to add
     */
    public void addTag(String tag) {
        if (tags == null) {
            tags = new HashSet<>();
        }
        tags.add(tag);
    }

    /**
     * @return the identifier
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * @param identifier the identifier to set
     */
    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    /**
     * @return the file
     */
    public String getFile() {
        return file;
    }

    /**
     * @param file the file to set
     */
    public void setFile(String file) {
        this.file = file;
    }

    /**
     * Set reliability to 1.0 for manually selected seed instances.
     */
    public void setIsSeed() {
    	this.reliability = 1.0;
    }

    /**
     * @return the number
     */
    public String getNumber() {
        return number;
    }

    /**
     * @param number the number (year, number, wave, ...) to set
     */
    public void setNumber(String number) {
        this.number = number;
    }

    public double getReliability() {
    	return this.reliability;
    }

    public boolean isReliable(Collection<InfolisPattern> reliablePatterns, int dataSize, Reliability r, double threshold) throws IOException, ParseException {
    	this.reliability = r.computeReliability(dataSize, reliablePatterns, this);
        if (this.getReliability() >= threshold) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return the associations
     */
    public Map<String, Double> getAssociations() {
        return associations;
    }

    /**
     * @param associations the associations to set
     */
    public void setAssociations(Map<String, Double> associations) {
        this.associations = associations;
    }

        public boolean addAssociation(String entityName, double score) {
        if (this.getAssociations().containsKey(entityName)) {
            log.debug("association between entity " + this.getName()
                    + " and entity " + entityName
                    + " already known, overwriting previously saved score.");
        }
        return (this.getAssociations().put(entityName, score) == null);
    }

    /**
     * @return the alternativeNames
     */
    public List<String> getAlternativeNames() {
        return alternativeNames;
    }

    /**
     * @param alternativeNames the alternativeNames to set
     */
    public void setAlternativeNames(List<String> alternativeNames) {
        this.alternativeNames = alternativeNames;
    }

    /**
     * @param alternativeNames the alternativeName to add
     */
    public void addAlternativeNames(String alternativeName) {
        this.alternativeNames.add(alternativeName);
    }
}
