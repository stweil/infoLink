package io.github.infolis.infolink.patternLearner;

import io.github.infolis.infolink.searching.Search_Term_Position;
import io.github.infolis.infolink.tagger.Tagger;
import io.github.infolis.model.Chunk;
import io.github.infolis.model.StudyContext;
import io.github.infolis.util.InfolisFileUtils;
import io.github.infolis.util.RegexUtils;
import io.github.infolis.util.SerializationUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.queryParser.ParseException;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import weka.core.Attribute;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

/**
 * Class for finding references to scientific datasets in publications using a minimum supervised iterative 
 * pattern induction approach. For a description of the basic algorithm see 
 * <emph>Boland, Katarina; Ritze, Dominique; Eckert, Kai; Mathiak, Brigitte (2012): Identifying references to 
 * datasets in publications. In: Zaphiris, Panayiotis; Buchanan, George; Rasmussen, Edie; Loizides, 
 * Fernando (Hrsg.): Proceedings of the Second International Conference on Theory and Practice of Digital 
 * Libraries (TDPL 2012), Paphos, Cyprus, September 23-27, 2012. Lecture notes in computer science, 7489, 
 * Berlin: Springer, S. 150-161. </emph>. Note that some features are not described in this publication.
 * 
 * @author katarina.boland@gesis.org
 * @version 2014-01-27
 *
 */
//TODO: SUBCLASSES OF LEARNER - RELIABILITY AND FREQUENCY LEARNERS - NEED DIFFERENT CLASS VARS
public class Learner
{
	List<List<String>> contextsAsStrings;
	Set<String> processedSeeds; //all processed seeds
	Set<String> foundSeeds_iteration; //seeds found at current iteration step
	Set<String[]> foundPatterns_iteration; //lucene query, minimal regex, full regex. For frequeny learner
	Set<String[]> relevantPatterns; //frequency learner
	Set<String> reliablePatterns_iteration; //reliability learner
	Set<String> processedPatterns; //may contain patterns that were judged not to be relevant - prevents multiple searches for same patterns (all learner)
	Map<String, List<String[]>> reliablePatternsAndContexts; //reliability learner
	Set<String> reliableInstances; //reliability learner
	Map<String, List<StudyContext>> extractedContexts; //todo: replace above...
	// basePath is used for normalizing path names when searching for known dataset names
	// should point to the path of the input text corpus
	Path basePath;
	boolean constraint_upperCase;
	String taggingCmd;
	String chunkingCmd;
	String language;
	String corpusPath;
	String indexPath;
	String trainPath;
	String contextPath;
	String arffPath;
	String outputPath;
	Reliability reliability;
	
	/**
	 * Class constructor specifying the constraints for patterns.
	 * 
	 * @param constraint_NP	if set, references are only accepted if assumed dataset name occurs in nominal phrase
	 * @param constraint_upperCase	if set, references are only accepted if dataset name has at least one upper case character
	 * 
	 */
	public Learner(String taggingCmd, String chunkingCmd, boolean constraint_upperCase, String corpusPath, String indexPath, String trainPath, String contextPath, String arffPath, String outputPath)
	{
		this.processedSeeds = new HashSet<String>();
		this.foundSeeds_iteration = new HashSet<String>();
		this.foundPatterns_iteration = new HashSet<String[]>();
		this.reliablePatterns_iteration = new HashSet<String>();
		this.processedPatterns = new HashSet<String>();
		this.constraint_upperCase = constraint_upperCase;
		this.taggingCmd = taggingCmd;
		this.chunkingCmd = chunkingCmd;
		this.corpusPath = corpusPath;
		this.basePath = Paths.get(corpusPath);
		this.indexPath = indexPath;
		this.trainPath = trainPath;
		this.contextPath = contextPath;
		this.arffPath = arffPath;
		this.outputPath = outputPath;
		this.reliablePatternsAndContexts = new HashMap<String, List<String[]>>();
		this.extractedContexts = new HashMap<String, List<StudyContext>>();
		this.reliableInstances = new HashSet<String>();
		this.reliability = new Reliability();
		this.relevantPatterns = new HashSet<String[]>();
	}
	
	
	//TODO: cite paper in docstring
	/**
	 * Class for storing pattern ranking and instance ranking reliability scores.
	 * (see:
	 * 
	 * @author katarina.boland@gesis.org
	 * @version 2015-01-05
	 */
	private class Reliability
	{
		Map<String, Instance> instances;
		Map<String, Pattern> patterns;
		double maximumPmi;
		
		/**
		 * Class constructor initializing empty sets for instances and patterns. 
		 */
		Reliability ( )
		{
			this.instances = new HashMap<String, Instance>();
			this.patterns = new HashMap<String, Pattern>();
			this.maximumPmi = 0;
		}

		/**
		 * Adds a new Instance instance. The instance may have been added before 
		 * with only a subset of all initializing patterns. Thus, when adding 
		 * a new instance, checks if an instance with the same name is already 
		 * known and if so, the new associations are added to the existing
		 * instance.
		 * 
		 * @param instance	Instance instance to be added
		 * @return			true, if instance was not included in this instances before, false if already in this instances
		 */
		boolean addInstance(Instance instance)
		{
			if(this.instances.containsKey(instance.name))
			{
				Instance curInstance = this.instances.get(instance.name);
				Map<String, Double> curAssociations = curInstance.associations;
				curAssociations.putAll(instance.associations);
				instance.associations = curAssociations;
				this.instances.put(instance.name, instance);
				return false;
			}
			this.instances.put(instance.name, instance);
			return true;
		}
		
		/**Adds a new Pattern instance. The pattern may have been added before 
		 * with only a subset of all extracted instances. Thus, when adding 
		 * a new pattern, checks if a pattern with the same name is already 
		 * known and if so, the new associations are added to the existing
		 * pattern.
		 * 
		 * @param pattern	Pattern instance to be added
		 * @return			true, if pattern was not included in this patterns before, false if already in this patterns
		 */
		boolean addPattern(Pattern pattern)
		{
			if(this.patterns.containsKey(pattern.pattern)) 
			{
				Pattern curPattern = this.patterns.get(pattern.pattern);
				Map<String, Double> curAssociations = curPattern.associations;
				curAssociations.putAll(pattern.associations);
				pattern.associations = curAssociations;
				this.patterns.put(pattern.pattern, pattern);
				return false;
			}
			this.patterns.put(pattern.pattern, pattern);
			return true;
		}

		/**
		 * Set this maximum to pmi if higher than the current maximum.
		 *  
		 * @param pmi	the new value to maybe become the new maximum
		 * @return		true, if pmi is the new maximum (or equal to the existing one), false otherwise (if lesser than maximum)
		 */
		boolean setMaxPmi(double pmi)
		{
			if (pmi >= this.maximumPmi) { this.maximumPmi = pmi; return true; }
			else { return false; }
		}
	
		/**
		 * Class for storing instance ranking reliability scores.
		 * 
		 * @author katarina.boland@gesis.org
		 * @version 2015-01-05
		 */
		class Instance 
		{
			private String name;
			private Map<String, Double> associations;
//			private double reliability;
			
			Instance (String name)
			{
				this.name = name;
				this.associations = new HashMap<String, Double>();
			}
			
			/**
			 * Adds an association between this instance and a specified pattern. 
			 * 
			 * @param pattern	the pattern whose association to store
			 * @param score		pmi score for this instance and pattern
			 * @return			true, if association is new; false if association was already known
			 */
			private boolean addAssociation(String pattern, double score)
			{
				if (this.associations.containsKey(pattern)) { System.err.print("Warning: association between instance " + this.name + " and pattern " + pattern + " already known!");} 
				return(this.associations.put(pattern, score) == null);
			}
		}
		
		/**
		 * Class for storing pattern ranking reliability scores.
		 * 
		 * @author katarina.boland@gesis.org
		 * @version 2015-01-05
		 */
		class Pattern
		{
			private String pattern;
			private Map<String, Double> associations;
//			double reliability;
			
			Pattern(String pattern)
			{
				this.pattern = pattern;
				this.associations = new HashMap<String, Double>();
			}
			
			/**
			 * Adds an association between this pattern and a specified instance. 
			 * 
			 * @param instance	the instance whose association to store
			 * @param score		pmi score for this pattern and instance
			 * @return			true, if association is new; false if association was already known
			 */
			private boolean addAssociation(String instanceName, double score)
			{
				if (this.associations.containsKey(instanceName)) { System.err.print( "Warning: association between pattern " + this.pattern + " and instance " + instanceName + " already known! "); } 
				return (this.associations.put(instanceName, score) == null);
			}
		}
	}
	
	
	
	/**
	 * Retrieves all training instances from the specified data having the specified class attribute. 
	 * Note: each instance is required to have exactly 10 attributes representing 5 words before 
	 * and 5 words after the dataset name + one class attribute. Instances having class attribute 
	 * <emph>True</emph> are positive examples for the relation <emph>IsStudyReference</emph>, 
	 * instances having class attribute <emph>False</emph> are negative examples.
	 * 
	 * @param data	the training examples to learn from
	 * @return		Instances having class <emph>classVal</emph>
	 */
	private Instances getInstances(Instances data, String classVal)
	{
		FastVector atts = new FastVector();
		FastVector attVals;
		atts.addElement(new Attribute("l5", (FastVector) null));
	    atts.addElement(new Attribute("l4", (FastVector) null));
	    atts.addElement(new Attribute("l3", (FastVector) null));
	    atts.addElement(new Attribute("l2", (FastVector) null));
	    atts.addElement(new Attribute("l1", (FastVector) null));
	    atts.addElement(new Attribute("r1", (FastVector) null));
	    atts.addElement(new Attribute("r2", (FastVector) null));
	    atts.addElement(new Attribute("r3", (FastVector) null));
	    atts.addElement(new Attribute("r4", (FastVector) null));
	    atts.addElement(new Attribute("r5", (FastVector) null));
	    attVals = new FastVector();
	    attVals.addElement(classVal);
	    Attribute classAttr = new Attribute("class", attVals);
	    atts.addElement(classAttr);
		Instances data_matchingClass = new Instances("IsStudyReference_" + classVal, atts, 0);
		data_matchingClass.setClass(classAttr);

		// iterate over instances, check value of class attribute
		// return only instances with classVal: disregard instances with other class
		@SuppressWarnings("unchecked")
		Enumeration<Instance> instanceEnum = data.enumerateInstances();
    	while(instanceEnum.hasMoreElements())
    	{	
    		Instance curInstance = instanceEnum.nextElement();
    		String curClassVal = curInstance.stringValue(curInstance.classAttribute());
    		if (curClassVal.equals(new String(classVal)))
    		{
    			Instance newInstance = new Instance(11);
    			newInstance.setDataset(data_matchingClass);
    			// loop over all attributes and fill in values
    			// copying values from an existing instance using 
    			// Instance newInstance = new Instance(curInstance);
    			// does not work...
    			for(int i=0; i<11; i++) { newInstance.setValue(i, curInstance.stringValue(i)); }
    			data_matchingClass.add(newInstance);
    		}
    	}
		return data_matchingClass;
	}
	
	
	/**
	 * Extracts the name of all seeds from a file listing one seed per line.
	 * 
	 * @param filename	name of the file listing all seeds
	 * @return			a set of all seeds contained in the file
	 */
	public Set<String> getSeeds(String filename) throws IOException
	{
		Set<String> seedList = new HashSet<String>();
		InputStreamReader isr = new InputStreamReader(new FileInputStream(new File(filename)), "UTF8");
		BufferedReader reader = new BufferedReader(isr);
		String text = null;
		while ((text = reader.readLine()) != null) {
			seedList.add(new String(text).trim());
		}
		reader.close();
		return seedList;
	}
	
	/**
	 * Calls <emph>getContextsForSeed</emph> method for all seeds contained in <emph>seedList</emph>.
	 * 
	 * @param indexDir			name of the directory containing the lucene index to be searched
	 * @param seedList			a set of seeds whose contexts to retrieve
	 */
	public List<StudyContext> getContextsForAllSeeds(String indexDir, Collection<String> seedList) throws IOException, ParseException {
		List<StudyContext> contexts = new ArrayList<StudyContext>();
		for (String seed : seedList) {
			try { contexts.addAll(getContextsForSeed(seed)); }
			catch (IOException ioe) { ioe.printStackTrace(); throw new IOException();}
			catch (ParseException pe) { pe.printStackTrace(); throw new ParseException();}
		}
		return contexts;
	}
	
	/**
	 * Searches for occurrences of the string <emph>seed</emph> in the lucene index at path <emph>indexDir</emph> 
	 * and returns its contexts.
	 * 
	 * @param indexDir	name of the directory containing the lucene index to be searched
	 * @param seed		seed for which the contexts to retrieve
	 */
	public List<StudyContext> getContextsForSeed(String seed) throws IOException, ParseException {
		List<StudyContext> contexts = new ArrayList<StudyContext>();
		Search_Term_Position search = new Search_Term_Position(this.indexPath, null, seed, Search_Term_Position.normalizeQuery(seed));
		try { contexts = search.complexSearch_getContexts(); }
		catch (IOException ioe) { ioe.printStackTrace(); throw new IOException();}
		catch (ParseException pe) { pe.printStackTrace(); throw new ParseException();}
		return contexts;
	}
	
	public void writeContextToXML(StudyContext context, String filename) throws IOException {
		try {
			InfolisFileUtils.prepareOutputFile(filename);
			InfolisFileUtils.writeToFile(new File(filename), "UTF-8", context.toXML(), true);
			InfolisFileUtils.completeOutputFile(filename);
		}
		catch (IOException ioe) { ioe.printStackTrace(); throw new IOException();}
	}
	
	
	/**
	 * Generates extraction patterns using an iterative bootstrapping approach. 
	 * 
	 * <ol>
	 * 		<li>searches for seeds in the specified corpus and extracts the surrounding words as contexts</li>
	 * 		<li>analyzes contexts and generates extraction patterns</li>
	 * 		<li>applies extraction patterns on corpus to extract new seeds</li>
	 * 		<li>continues with 1) until maximum number of iterations is reached</li>
	 * 		<li>outputs found seeds, contexts and extraction patterns</li>
	 * </ol>
	 * 
	 * Method for assessing pattern validity is reliability-based.
	 * 
	 * @param seeds				reliable terms to be searched as starting point
	 * @param threshold			reliability threshold
	 **/
	//TODO: NOT NECESSARY, CALL METHOD DIRECTLY :D
	private void bootstrap(Collection<String> seeds, double threshold, int maxIterations) throws IOException, ParseException
	{
		try { bootstrap_reliabilityBased(seeds, threshold, -1, maxIterations); }
		catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
		catch(ParseException pe) { pe.printStackTrace(); throw(new ParseException()); }
	}
	
	/**
	 * Generates extraction patterns using an iterative bootstrapping approach. 
	 * 
	 * <ol>
	 * 		<li>searches for seeds in the specified corpus and extracts the surrounding words as contexts</li>
	 * 		<li>analyzes contexts and generates extraction patterns</li>
	 * 		<li>applies extraction patterns on corpus to extract new seeds</li>
	 * 		<li>continues with 1) until maximum number of iterations is reached</li>
	 * 		<li>outputs found seeds, contexts and extraction patterns</li>
	 * </ol>
	 * 
	 * Method for assessing pattern validity is frequency-based.
	 * 
	 * @param seed				the term to be searched as starting point in the current iteration
	 * @param threshold			threshold for accepting patterns
	 * @param maxIterations		maximum number of iterations for algorithm
	 *
	 */
	private void bootstrap_frequency(Collection<String> terms, int numIter, double threshold, int maxIterations, String strategy) throws IOException, ParseException
	{
		Set<String[]> newPatterns = new HashSet<String[]>();
		List<StudyContext> contexts_currentIteration = new ArrayList<StudyContext>();
		numIter ++;
		try {
			for(String seed : terms) {
			
				// 1. use lucene index to search for term in corpus
				List<StudyContext> contexts = getContextsForSeed(seed);
				contexts_currentIteration.addAll(contexts);
				this.extractedContexts.put(seed, contexts);
				System.out.println("Processing contexts for seed " + seed);
				// 2. generate patterns
				//TODO: ENUM TYPES FOR STRATEGIES
				if (strategy.equals("separate")) {
					Set<String[]> patterns = inducePatterns(contexts, threshold);
					this.relevantPatterns.addAll(patterns);
					newPatterns.addAll(patterns);
				}
			}
			if (strategy.equals("mergeCurrent")) { 
				Set<String[]> patterns = inducePatterns(contexts_currentIteration, threshold); 
				this.relevantPatterns.addAll(patterns);
				newPatterns.addAll(patterns);
			}
			//TODO: add mergeAll and mergeNew
			// 3. search for patterns in corpus
			//TODO: RETURN CONTEXT INSTANCE HERE! Adjust regex part for this
			List<String[]> res = applyPatterns(newPatterns);
			
			Set<String> newSeeds = new HashSet<String>();
			for (String[] entry : res) {
				String studyName = entry[0];
				// TODO
				// TODO
//				String context = entry[1];
//				String corpusFilename = entry[2];
//				String usedPat = entry[3];
				newSeeds.add(studyName);
			}
			this.processedSeeds.addAll(terms); 
			System.out.println("Found " + newSeeds.size() + " new seeds in current iteration");
			//TODO: NO NEED TO SEARCH FOR ALL PATTERNS AGAIN, INSTEAD USE STORED RESULTS AND SEARCH ONLY FOR NEW PATTERNS
			if (numIter >= maxIterations -1) { 
				System.out.println("Reached maximum number of iterations! Applying learnt patterns..."); 
				// new learner instance needed, else previously processed patterns are ignored
				//TODO: INSERT CORRECT VALUES FOR CHUNKING CONSTRAINT
				Learner newLearner = new Learner(null, null, this.constraint_upperCase, this.corpusPath, this.indexPath, this.trainPath, this.contextPath, this.arffPath, this.outputPath);
				List<String[]> resultList = newLearner.applyPatterns(this.relevantPatterns);
				outputContextsAndPatterns(resultList, this.outputPath + File.separator + "contexts.xml", this.outputPath + File.separator + "patterns.csv", this.outputPath + File.separator + "datasets.csv");
				return; 
			}
			else { bootstrap_frequency(newSeeds, numIter, threshold, maxIterations, strategy); }
		}
		catch (ParseException pe) { pe.printStackTrace(); throw new ParseException(); }
		catch (IOException ioe) { ioe.printStackTrace(); throw new IOException(); }
	}
	
	/**
	 * Main method for reliability-based bootstrapping.
	 * 
	 * @param terms		reliable seed terms for current iteration
	 * @param threshold	reliability threshold
	 * @param numIter	current iteration
	 */
	private void bootstrap_reliabilityBased(Collection<String> terms, double threshold, int numIter, int maxIter) throws IOException, ParseException
	{
		numIter ++;
		System.out.println("Bootstrapping... Iteration: " + numIter);
		File logFile = new File(this.outputPath + File.separator + "output.txt");
		// 0. filter seeds, select only reliable ones
		// alternatively: use all seeds extracted by reliable patterns
		this.reliableInstances.addAll(terms);
		this.foundSeeds_iteration = new HashSet<String>();
		this.reliablePatterns_iteration = new HashSet<String>();
		// 1. search for all seeds and save contexts
		for (String seed : terms)
		{
			System.out.println( "Bootstrapping with seed " + seed);
			try { 
				List<StudyContext> contexts = getContextsForSeed(seed);
				this.extractedContexts.put(seed, contexts); 
			}
		    catch (ParseException pe) { pe.printStackTrace(); throw new ParseException(); }
		    catch (IOException ioe) { ioe.printStackTrace(); throw new IOException(); }
		}
		// 2. get reliable patterns, save their data to this.reliablePatternsAndContexts and 
		// new seeds to this.foundSeeds_iteration
		for (String seed : terms) { saveReliablePatternData(this.extractedContexts.get(seed), threshold); }
		String output_iteration = getString_reliablePatternOutput( this.reliablePatternsAndContexts, numIter );
		//TODO: output trace of decisions... (reliability of patterns, change in trusted patterns, instances...)
		try { InfolisFileUtils.writeToFile(logFile, "utf-8", output_iteration, true); }
		catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
		//TODO: USE DIFFERENT STOP CRITERION: continue until patterns stable...
		if(numIter >= maxIter - 1) { System.out.println("Reached maximum number of iterations! Returning."); return; }
		bootstrap_reliabilityBased(this.foundSeeds_iteration, threshold, numIter, maxIter);
	}
	
	private String getString_reliablePatternOutput( Map<String, List<String[]>> patternsAndContexts, int iteration )
	{
		String string = "Iteration " + iteration + ":\n";
		for ( String pattern : patternsAndContexts.keySet() )
		{
			string += "\tPattern " + pattern + "\n";
			for ( String[] context : patternsAndContexts.get( pattern ) )
			{
				String contextString = "";
				for ( String entry : context ) { contextString += entry + " "; }
				string += "\t\t" + contextString.trim() + "\n";
			}
		}
		return string;	
	}


	//TODO: USE METHODS FROM TOOLS PACKAGE
	// writing the patterns to file here is used for validation and evaluation purposes 
	// storing patterns not as set but as list would allow to rank them according to number 
	// of contexts extracted with it
	/**
	 * ...
	 * 
	 * @param studyNcontextList	...
	 * @param filenameContexts	...
	 * @param filenamePatterns	...
	 * @param filenameStudies	...
	 */
	private void outputContextsAndPatterns(List<String[]> studyNcontextList, String filenameContexts, String filenamePatterns, String filenameStudies) throws IOException
	{
		File contextFile = new File(filenameContexts);
		File patternFile = new File(filenamePatterns);
		File studyFile = new File(filenameStudies);
		// write all these files for validation, do not actually read from them in the program
		//TODO: do not put everything in the try statement, writing everything to file is not mandatory
		OutputStreamWriter fstream = new OutputStreamWriter(new FileOutputStream(contextFile), "UTF-8");
		BufferedWriter out = new BufferedWriter(fstream);
		out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + System.getProperty("line.separator") +"<contexts>" + System.getProperty("line.separator"));
			
		OutputStreamWriter fstream2 = new OutputStreamWriter(new FileOutputStream(patternFile), "UTF-8");
		BufferedWriter out2 = new BufferedWriter(fstream2);
			
		OutputStreamWriter fstream3 = new OutputStreamWriter(new FileOutputStream(studyFile), "UTF-8");
		BufferedWriter out3 = new BufferedWriter(fstream3);
			
		Set<String> patSet = new HashSet<String>();
		Set<String> studySet = new HashSet<String>();
			
		for (String[] studyNcontext: studyNcontextList)
		{
			String studyName = studyNcontext[0];
			String context = studyNcontext[1];
			String corpusFilename = studyNcontext[2];
			String usedPat = studyNcontext[3];
				
			context = SerializationUtils.escapeXML(context);
			
			patSet.add(usedPat);
			studySet.add(studyName);
			// split context into words
			// join first 5 words = left context
			// last 5 words = rightcontext
			// middle word = studyname
			// do not split at study name - in rare cases, it might occur more than once!
			String[] contextList = context.replace(System.getProperty("line.separator"), " ").replace("\n", " ").replace("\r", " ").trim().split("\\s+");
			String leftContext;
			String rightContext;
			// contextList may contain less entries if the word before or after the study name is directly attached to the study name (e.g. ALLBUS-Daten)
			if (contextList.length != 10 + studyName.trim().split("\\s+").length)
			{
				// split by study name in this case...
				//TODO: simple split at first occurrence only :) (split(term,limit))
				contextList = context.split(Pattern.quote(SerializationUtils.escapeXML(studyName)));
				if (contextList.length != 2)
				{
					System.err.println("Warning: context does not have 10 words and cannot be split around the study name. Ignoring.");
					for (String contextErr : contextList) { System.err.println("###" + contextErr + "###"); }
					System.err.println(studyName);
					System.err.println(contextList.length);
					continue;
				}
				else
				{
					leftContext = contextList[0];
					rightContext = contextList[1];
				}
			}
			else
			{
				leftContext = "";
				rightContext = "";
				for (int i = 0; i < 5; i++)
				{
					leftContext += contextList[i] + " ";
				}
				for ( int i = contextList.length -1 ; i >= contextList.length - 5; i--)
				{
					rightContext = contextList[i] + " " + rightContext;
				}
			}
			out.write("\t<context term=\"" + SerializationUtils.escapeXML(studyName) + "\" document=\"" + SerializationUtils.escapeXML(corpusFilename) + "\" usedPattern=\"" + SerializationUtils.escapeXML(usedPat) + "\">" + System.getProperty("line.separator") + "\t\t<leftContext>" + leftContext.trim() +"</leftContext>" + System.getProperty("line.separator") + "\t\t<rightContext>" + rightContext.trim() + "</rightContext>" + System.getProperty("line.separator") + "\t</context>" + System.getProperty("line.separator"));
		}
			
		out.write(System.getProperty("line.separator") + "</contexts>" + System.getProperty("line.separator"));
		out.close();
		this.foundSeeds_iteration.addAll( studySet );
			
		for (String pat : patSet) {	out2.write(pat + System.getProperty("line.separator"));	}
		out2.close();
		
		for (String stud : studySet) { out3.write(stud + System.getProperty("line.separator"));	}
		out3.close();
	}
	
	//TODO: remove duplicate code... use separate method instead
	/**
	 * ...
	 * 
	 * @param studyNcontextList	...
	 * @param filenameContexts	...
	 * @param filenamePatterns	...
	 * @param filenameStudies	...
	 */
	private void outputContextsAndPatterns_distinct (List<String[]> studyNcontextList, String filenameContexts, String filenamePatterns, String filenameStudies, boolean train) throws IOException
	{
		File contextFile = new File(filenameContexts);
		File patternFile = new File(filenamePatterns);
		File studyFile = new File(filenameStudies);
		
		//TODO: inserted "true" ...
		OutputStreamWriter fstream = new OutputStreamWriter(new FileOutputStream(contextFile, true), "UTF-8");	
		BufferedWriter out = new BufferedWriter(fstream);
		if (train) { out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + System.getProperty("line.separator") + "<contexts>" + System.getProperty("line.separator")); }
			
		OutputStreamWriter fstream2 = new OutputStreamWriter(new FileOutputStream(patternFile, true), "UTF-8");
		BufferedWriter out2 = new BufferedWriter(fstream2);
			
		OutputStreamWriter fstream3 = new OutputStreamWriter(new FileOutputStream(studyFile, true), "UTF-8");
		BufferedWriter out3 = new BufferedWriter(fstream3);
			
		Set<String> patSet = new HashSet<String>();
		Set<String> studySet = new HashSet<String>();
		Set<String> distinctContexts = new HashSet<String>();
			
		for (String[] studyNcontext: studyNcontextList)
		{
			String studyName = studyNcontext[0];
			String context = studyNcontext[1];
			String corpusFilename = studyNcontext[2];
			String usedPat = studyNcontext[3];
			
			context = SerializationUtils.escapeXML(context);
			
			patSet.add(usedPat);
			studySet.add(studyName);
			
			// split context into words
			// join first 5 words = left context
			// last 5 words = rightcontext
			// middle word = studyname
			String[] contextList = context.replace(System.getProperty("line.separator"), " ").replace("\n", " ").replace("\r", " ").trim().split("\\s+");
			// do not print duplicate contexts
			// extend check for duplicate contexts: context as part of study name...
			if (! distinctContexts.contains(context.replace("\\s+", "")))
			{
				String leftContext;
				String rightContext;
				if (contextList.length != 10 + studyName.trim().split("\\s+").length)
				{
					// split by study name in this case...
					// TODO: (s.o.)
					contextList = context.split(Pattern.quote(SerializationUtils.escapeXML(studyName)));
					if (contextList.length != 2)
					{
						System.err.println("Warning: context has not 10 words and cannot by split around the study name. Check output method.");
						for (String contextErr : contextList) { System.err.println("###" + contextErr + "###"); }
						System.err.println(studyName);
						System.err.println(contextList.length);
						continue;
					}
					else
					{
						leftContext = contextList[0];
						rightContext = contextList[1];
					}
				}
				else
				{
					leftContext = "";
					rightContext = "";
					for (int i = 0; i < 5; i++)
					{
						leftContext += contextList[i] + " ";
					}
					for ( int i = contextList.length -1 ; i >= contextList.length - 5; i--)
					{
						rightContext = contextList[i] + " " + rightContext;
					}
				}
				out.write("\t<context term=\"" + SerializationUtils.escapeXML(studyName) + "\" document=\"" + SerializationUtils.escapeXML(corpusFilename) + "\" usedPattern=\"" + SerializationUtils.escapeXML(usedPat) + "\">" + System.getProperty("line.separator") + "\t\t<leftContext>" + leftContext.trim() +"</leftContext>" + System.getProperty("line.separator") + "\t\t<rightContext>" + rightContext.trim() + "</rightContext>" + System.getProperty("line.separator") + "\t</context>" + System.getProperty("line.separator"));
				distinctContexts.add(context.replace("\\s+", ""));
			}
		}
		if (train) { out.write(System.getProperty("line.separator") + "</contexts>" + System.getProperty("line.separator")); }
		out.close();
		
		for (String pat : patSet)
		{
			out2.write(pat + System.getProperty("line.separator"));
		}
		out2.close();
		
		for (String stud : studySet)
		{
			out3.write(stud + System.getProperty("line.separator"));
		}
		out3.close();
	}
	
	/**
	 * Writes instances in TrainingSet at <emph>filename</emph> to Weka's arff file format. 
	 * All instances in the training set are assumed to be positive training examples (thus 
	 * receiving the class value <emph>True</emph>). Name of the output file equals the name 
	 * of the training set file having ".arff" as extension instead of ".xml".
	 * 
	 * @param filename	name of the TrainingSet XML file
	 */
	private void outputArffFile(String filename) throws IOException
	{
		TrainingSet newTrainingSet = new TrainingSet(new File(filename));
		//TODO: assumes patterns to be correct
		try { newTrainingSet.createTrainingSet("True",filename.replace(".xml", ".arff")); }
		catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
	}
	
	/**
	 * Writes dataset names, contexts and patterns to the files specified in <emph>filenames</emph> 
	 * and creates an arff file for using the contexts as training set.
	 * 
	 * @param studyNcontextList list of extracted instances and their contexts
	 * @param filenames			array specifying the names for the distinct output files ([0]: dataset names, [1]: contexts, [2]: patterns)
	 */
	private void output(List<String[]> studyNcontextList, String[] filenames) throws IOException
	{
		String filenameStudies = filenames[0];
		String filenameContexts = filenames[1];
		String filenamePatterns = filenames[2];
		try { outputContextsAndPatterns(studyNcontextList, filenameContexts, filenamePatterns, filenameStudies); }
		catch (IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
		try { outputArffFile(filenameContexts); }
		catch  (IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
	}
	
	/**
	 * Writes dataset names, contexts and patterns to the files specified in <emph>filenames</emph>. 
	 * Filters out any duplicates in the process. 
	 * If in training mode, additionally creates an arff file for using the contexts as training set.
	 * 
	 * @param studyNcontextList	list of extracted instances and their contexts
	 * @param filenames			array specifying the names for the distinct output files ([0]: dataset names, [1]: contexts, [2]: patterns)
	 * @param train				flag specifying whether InfoLink is in training mode (i.e. learning new patterns instead of applying known ones)
	 */
	private void output_distinct(List<String[]> studyNcontextList, String[] filenames, boolean train) throws IOException
	{
		String filenameStudies = filenames[0];
		String filenameContexts = filenames[1];
		String filenamePatterns = filenames[2];
		try { outputContextsAndPatterns_distinct(studyNcontextList, filenameContexts, filenamePatterns, filenameStudies, train); }
		catch  (IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
		if (train) { 
			try { outputArffFile(filenameContexts); }
			catch  (IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
		}
	}
	
	/**
	 * Applies a list of patterns on the text corpus to extract new dataset references.
	 * 
	 * @param patterns	list of patterns. Each pattern consists of a lucene query, a simple regular 
	 * expression for computing reliability score and a more complex regular expression for extracting 
	 * references with contexts
	 * @param corpus	list of filenames of the text corpus
	 * @param indexPath	path of the lucene index for the text corpus
	 * @return			a list of study references
	 */
	private List<String[]> getStudyRefs_optimized_reliabilityCheck(Set<String[]> patterns, String[] corpus) throws IOException, ParseException
	{
		List<String[]> resAggregated = new ArrayList<String[]>();
		
		for (String curPat[] : patterns)
		{
			// get list of documents in which to search for the regular expression
			try { 
				String[] candidateCorpus = getStudyRef_lucene(curPat[0]); 
				Set<String> patSet = new HashSet<String>();
				patSet.add(curPat[2]);
				resAggregated.addAll(getStudyRefs(patSet, candidateCorpus));
			}
			catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
			catch(ParseException pe) { pe.printStackTrace(); throw(new ParseException()); }
			continue;
		}
		System.out.println("Done processing complex patterns. Continuing.");
		return resAggregated;
	}
	
	/**
	 * Applies a list of patterns on the text corpus to extract new dataset references.
	 * 
	 * Retrieves candidate documents containing all the words first using lucene index,
	 * then searches for regular expressions in these candidates to extract contexts
	 * 
	 * @param patterns	list of patterns. Each pattern consists of a lucene query and a 
	 * regular expression for extracting references with contexts
	 * @param corpus	list of filenames of the text corpus
	 * @param indexPath	path of the lucene index for the text corpus
	 * @return			a list of study references
	 */
	private List<String[]> getStudyRefs_optimized(Set<String[]> patterns, String[] corpus) throws IOException, ParseException
	{
		List<String[]> resAggregated = new ArrayList<String[]>();
		
		for (String curPat[] : patterns)
		{
			try { 
				String[] candidateCorpus = getStudyRef_lucene(curPat[0]); 
				Set<String> patSet = new HashSet<String>();
				patSet.add(curPat[1]);
				//TODO: see above...
				try { resAggregated.addAll(getStudyRefs(patSet, candidateCorpus)); }
				catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
				this.processedPatterns.add(curPat[1]);
				continue;
			}
			catch(ParseException pe) { pe.printStackTrace(); throw(new ParseException()); }
	
		}
		System.out.println("Done processing complex patterns. Continuing.");
		return resAggregated;
	}
	
	/**
	 * Search for lucene query in index at this indexPath and return documents with hits.
	 * 
	 * @param lucene_pattern	lucene search query
	 * @return					a list of documents with hits
	 */
	private String[] getStudyRef_lucene(String lucene_pattern) throws IOException, ParseException
	{
		String[] candidateCorpus;
		try
		{
			// lucene query is assumed to be normalized
			Search_Term_Position candidateSearcher = new Search_Term_Position(this.indexPath, "", "", lucene_pattern);
			candidateCorpus = candidateSearcher.complexSearch();
			//if(candidateCorpus.length < 1) { System.err.println("Warning: found no candidate documents. Check pattern."); throw new ParseException(); }
		}
		catch(IOException ioe) { ioe.printStackTrace(); throw new IOException(); }
		catch(ParseException pe) { pe.printStackTrace(); throw new ParseException(); }
		System.out.println("Done processing lucene query. Continuing.");
		return candidateCorpus;
	}
	
	/**
	 * Searches for dataset references in the documents contained in <emph>corpus</emph> 
	 * using the regular expressions in <emph>patterns</emph>
	 * 
	 * @param patterns	set of regular expressions for identification of dataset references
	 * @param corpus	array of filenames of text documents to search patterns in
	 * @return			a list of extracted contexts
	 */
	private List<String[]> getStudyRefs(Set<String> patterns, String[] corpus) throws IOException
	{
		List<String[]> resAggregated = new ArrayList<String[]>();
		for (String filename: corpus)
		{
			System.out.println("searching for patterns in " + filename);
			List<String[]> resList = searchForPatterns(patterns, filename, "");
			resAggregated.addAll(resList);
		}
		return resAggregated;
	}
	
	/**
	 * Searches for dataset references in the documents contained in <emph>corpus</emph> 
	 * using the regular expressions in <emph>patterns</emph> and outputs all found instances, contexts, 
	 * patterns and processed documents 
	 * 
	 * @param patterns		set of regular expressions for identification of dataset references
	 * @param corpus		array of filenames of text documents to search patterns in
	 * @param path_output	output path
	 * @return				a list of extracted contexts
	 */
	private List<String[]> getStudyRefs(Set<String> patterns, String[] corpus, String path_output) throws IOException
	{
		List<String[]> resAggregated = new ArrayList<String[]>();

		for (String filename: corpus)
		{
			System.out.println("searching for patterns in " + filename);
			List<String[]> resList = searchForPatterns(patterns, filename, "");
			String fileLogPath = path_output + File.separator + "processedDocs.csv";
			String[] filenames_grams = new String[3];
			filenames_grams[0] = path_output + File.separator + "datasets.csv";
			filenames_grams[1] = path_output + File.separator + "contexts.xml";
			filenames_grams[2] = path_output + File.separator + "patterns.csv";
			try { output_distinct(resList, filenames_grams, false); }
			catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
			OutputStreamWriter fstream = new OutputStreamWriter(new FileOutputStream(fileLogPath, true), "UTF-8");
			BufferedWriter out = new BufferedWriter(fstream);
			out.write(new File(filename).getAbsolutePath() + System.getProperty("line.separator"));
			out.close();
				
			resAggregated.addAll(resList);
		}
		return resAggregated;
	}
	
	/**
	 * Runnable implementation of the matcher.find() method for handling catastropic backtracking. 
	 * May be passed to a thread to be monitored and cancelled in case catastrophic backtracking occurs 
	 * while searching for a regular expression.
	 * 
	 * @author katarina.boland@gesis.org
	 *
	 */
	private static class SafeMatching implements Runnable
	{
		Matcher matcher;
		boolean find;
		
		/**
		 * Class constructor initializing the matcher.
		 * 
		 * @param m	the Matcher instance to be used for matching
		 */
		SafeMatching(Matcher m) { this.matcher = m; }
		
		@Override public void run()
		{
			this.find = this.matcher.find();
		}
	}
	
	//TODO: remove filenameout
	//TODO: LOGFILE LOCATION AS PARAM
	//rather: returns all mathces as...
	/**
	 * Searches all regex in <emph>patternSet</emph> in the specified text file <emph>filenameIn</emph> 
	 * and writes all matches to the file <emph>filenameOut</emph>. 
	 * 
	 * Creates file data/abortedMatches.txt to log all documents where matching 
	 * was aborted in suspicion of catastrophic backtracking
	 * 
	 * @param patternSet	set of regular expressions for searching
	 * @param filenameIn	name of the input text file to be searched in
	 * @param filenameOut	name of the output file for saving all matches
	 * @return				list of extracted contexts
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private List<String[]> searchForPatterns(Set<String> patternSet, String filenameIn, String filenameOut) throws FileNotFoundException, IOException
	{
		// search for each given regex in filenameIn
		// write new training examples to context / arff file...
		char[] content = new char[(int) new File(filenameIn).length()];
		Reader readerStream = new InputStreamReader(new FileInputStream(filenameIn), "UTF-8");
		BufferedReader reader = new BufferedReader(readerStream);
		reader.read(content);
		reader.close();
		String input = new String(content);
		// makes regex matching a bit easier
		String inputClean = input.replaceAll("\\s+", " ");
		
		List<String[]> res = new ArrayList<String[]>();
		Iterator<String> patIter = patternSet.iterator();
		while (patIter.hasNext())
		{
			String curPat = patIter.next();
			
			System.out.println("Searching for pattern " + curPat);
			// if pattern was processed before, ignore
			//if (this.processedPatterns.contains(curPat)) { continue; }
			
			Pattern p = Pattern.compile(curPat);
			Matcher m = p.matcher(inputClean);
			
			// call m.find() as a thread: catastrophic backtracking may occur which causes application 
			// to hang
			// thus monitor runtime of threat and terminate if processing takes too long
			SafeMatching safeMatch = new SafeMatching(m);
			Thread thread = new Thread(safeMatch, filenameIn + "\n" + curPat);
			long startTimeMillis = System.currentTimeMillis();
			// processing time for documents depends on size of the document. 
			// Allow 1024 milliseconds per KB
			long fileSize = new File(filenameIn).length(); 
			long maxTimeMillis = fileSize;
			// set upper limit for processing time - prevents stack overflow caused by monitoring process 
			// (threadCompleted)
			// 750000 suitable for -Xmx2g -Xms2g
			// if ( maxTimeMillis > 750000 ) { maxTimeMillis = 750000; }
			if(maxTimeMillis > 75000) { maxTimeMillis = 75000; }
			thread.start();
			boolean matchFound = false;
			// if thread was aborted due to long processing time, matchFound should be false
			if (threadCompleted(thread, maxTimeMillis, startTimeMillis)) { matchFound = safeMatch.find; }
			// TODO don't hardcode this path
			else { InfolisFileUtils.writeToFile( new File( "data/abortedMatches.txt" ), "utf-8", filenameIn + ";" + curPat + "\n", true ); }
			
			while(matchFound)
			{
				System.out.println("found pattern " + curPat + " in " + filenameIn);
				String context = m.group();
				String studyName = m.group(1).trim();
				// if studyname contains no characters ignore
				//TODO: not accurate - include accents etc in match... \p{M}?
				if (studyName.matches("\\P{L}+")) 
				{ 
					System.out.println("Searching for next match of pattern " + curPat);
					thread = new Thread(safeMatch, filenameIn + "\n" + curPat);
					thread.start();
					matchFound = false;
					// if thread was aborted due to long processing time, matchFound should be false
					if(threadCompleted(thread, maxTimeMillis, startTimeMillis)) { matchFound = safeMatch.find; }
					// TODO don't hardcode this path
					else { InfolisFileUtils.writeToFile(new File("data/abortedMatches.txt" ), "utf-8", filenameIn + ";" + curPat + "\n", true); }
					System.out.println( "Processing new match..." );
					continue;
				}
				// a study name is supposed to be a named entity and thus contain at least one upper-case 
				// character 
				// supposedly does not filter out many wrong names in German though
				if (constraint_upperCase) 
				{	
					if (studyName.toLowerCase().equals(studyName)) 
					{ 
						System.out.println( "Searching for next match of pattern " + curPat);
						thread = new Thread(safeMatch, filenameIn + "\n" + curPat);
						thread.start();
						matchFound = false;
						// if thread was aborted due to long processing time, matchFound should be false
						if (threadCompleted( thread, maxTimeMillis, startTimeMillis)) { matchFound = safeMatch.find; }
						// TODO don't hardcode this path
						else { InfolisFileUtils.writeToFile( new File( "data/abortedMatches.txt" ), "utf-8", filenameIn + ";" + curPat + "\n", true ); }
						System.out.println("Processing new match...");
						continue;
					} 
				}
				
				boolean containedInNP;
				if (this.chunkingCmd != null)
				{
					Tagger tagger;
					try { tagger = new Tagger(this.taggingCmd, this.chunkingCmd, "utf-8"); }
					catch (Exception e) { e.printStackTrace(); throw new IOException("\nerror initializing tagger\n"); }
					List<Chunk> nounPhrase = tagger.chunk(context).get("<NC>");
					containedInNP = false;
					if(nounPhrase != null)
					{
						for(Chunk chunk : nounPhrase)
						{
							if(chunk.getString().replaceAll("\\s", "").contains(studyName.replaceAll("\\s","")))
							{
								containedInNP = true;
							}
						}
					}
				}
				else { containedInNP = true; }
				
				if (containedInNP)
				{
					String[] studyNcontext = new String[4];
					studyNcontext[0] = studyName;
					studyNcontext[1] = context;
					studyNcontext[2] = filenameIn;
					studyNcontext[3] = curPat;
					res.add(studyNcontext);
					System.out.println("Added context.");
				}

				System.out.println("Searching for next match of pattern " + curPat);
				thread = new Thread(safeMatch, filenameIn + "\n" + curPat);
				thread.start();
				matchFound = false;
				// if thread was aborted due to long processing time, matchFound should be false
				if (threadCompleted( thread, maxTimeMillis, startTimeMillis)) { matchFound = safeMatch.find; }
				// TODO don't hardcode this path
				else { InfolisFileUtils.writeToFile(new File("data/abortedMatches.txt"), "utf-8", filenameIn + ";" + curPat + "\n", true ); }
				System.out.println( "Processing new match...");
			}
		}
		System.out.println( "Done searching for patterns in " + filenameIn);
		return res;
	}

	/**
	 * Construct extraction patterns, assess their validity and return relevant patterns 
	 * 
	 * @param instance	...
	 * @param data		training data
	 * @return			...
	 */
	@SuppressWarnings("unused")
	private List<String[]> getRelevantNgramPatterns(Instance instance, Instances data, double threshold)
	{
		String attVal0 = instance.stringValue(0); //l5
		String attVal1 = instance.stringValue(1); //l4
		String attVal2 = instance.stringValue(2); //l3
		String attVal3 = instance.stringValue(3); //l2
		String attVal4 = instance.stringValue(4); //l1
		String attVal5 = instance.stringValue(5); //r1
		String attVal6 = instance.stringValue(6); //r2
		String attVal7 = instance.stringValue(7); //r3
		String attVal8 = instance.stringValue(8); //r4
		String attVal9 = instance.stringValue(9); //r5
		
		//TODO: CONSTRUCT LUCENE QUERIES ONLY WHEN NEEDED (BELOW)
		String attVal0_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal0);
		String attVal1_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal1);
		String attVal2_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal2);
		String attVal3_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal3);
		String attVal4_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal4);
		String attVal5_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal5);
		String attVal6_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal6);
		String attVal7_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal7);
		String attVal8_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal8);
		String attVal9_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal9);

		String attVal0_quoted = Pattern.quote(attVal0);
		String attVal1_quoted = Pattern.quote(attVal1);
		String attVal2_quoted = Pattern.quote(attVal2);
		String attVal3_quoted = Pattern.quote(attVal3);
		String attVal4_quoted = Pattern.quote(attVal4);
		String attVal5_quoted = Pattern.quote(attVal5);
		String attVal6_quoted = Pattern.quote(attVal6);
		String attVal7_quoted = Pattern.quote(attVal7);
		String attVal8_quoted = Pattern.quote(attVal8);
		String attVal9_quoted = Pattern.quote(attVal9);
				
		String attVal4_regex = RegexUtils.normalizeAndEscapeRegex(attVal4);
		String attVal5_regex = RegexUtils.normalizeAndEscapeRegex(attVal5);
			
		//...
		if (attVal4.matches(".*\\P{Punct}")) 
		{ 
			attVal4_quoted += "\\s"; 
			attVal4_regex += "\\s"; 
		}
		if (attVal5.matches("\\P{Punct}.*")) 
		{ 
			attVal5_quoted = "\\s" + attVal5_quoted; 
			attVal5_regex = "\\s" + attVal5_regex; 
		}
		
		// two words enclosing study name
		String luceneQuery1 = "\"" + attVal4_lucene + " * " + attVal5_lucene + "\"";
		String regex_ngram1_quoted = attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
		String regex_ngram1_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 2 words behind study title + fixed word before
		String luceneQueryA = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + "\""; 
		String regex_ngramA_quoted = Pattern.quote(attVal4) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + Pattern.quote(attVal5) + "\\s" + Pattern.quote(attVal6);
		String regex_ngramA_normalizedAndQuoted = RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 2 words behind study title + (any) word found in data before!
		// (any word cause this pattern is induced each time for each different instance having this phrase...)
		String luceneQueryA_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + "\""; 
		String regex_ngramA_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted; 
		//String regex_ngramA_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 3 words behind study title + fixed word before
		String luceneQueryB = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + "\""; 
		String regex_ngramB_quoted = attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted;
		String regex_ngramB_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		String luceneQueryB_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + "\""; 
		String regex_ngramB_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted;
		//String regex_ngramB_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 4 words behind study title + fixed word before
		String luceneQueryC = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + "\""; 
		String regex_ngramC_quoted = attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted + "\\s" + attVal8_quoted;
		String regex_ngramC_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.lastWordRegex;
				
		String luceneQueryC_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + "\""; 
		String regex_ngramC_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted + "\\s" + attVal8_quoted;
		//String regex_ngramC_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 5 words behind study title + fixed word before
		String luceneQueryD = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + " " + attVal9_lucene + "\""; 
		String regex_ngramD_quoted = attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted + "\\s" + attVal8_quoted + "\\s" + attVal9_quoted;
		String regex_ngramD_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal9);

		// now the pattern can emerge at other positions, too, and is counted here as relevant...
		String luceneQueryD_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + " " + attVal9_lucene + "\""; 
		String regex_ngramD_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted + "\\s" + attVal8_quoted + "\\s" + attVal9_quoted;
		//String regex_ngramD_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal9);
		
		// phrase consisting of 2 words before study title + fixed word behind
		String luceneQuery2 = "\"" + attVal3_lucene + " " + attVal4_lucene  + " * " + attVal5_lucene + "\""; 
		String regex_ngram2_quoted = attVal3_quoted + "\\s" + attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
		String regex_ngram2_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		String luceneQuery2_flex = "\"" + attVal3_lucene + " " + attVal4_lucene + "\""; 
		String regex_ngram2_flex_quoted = attVal3_quoted + "\\s" + attVal4_quoted;
		//String regex_ngram2_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 3 words before study title + fixed word behind
		String luceneQuery3 = "\"" + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + " * " + attVal5_lucene + "\""; 
		String regex_ngram3_quoted = attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
		String regex_ngram3_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		String luceneQuery3_flex = "\"" + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + "\""; 
		String regex_ngram3_flex_quoted = attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted;
		//String regex_ngram3_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 4 words before study title + fixed word behind
		String luceneQuery4 = "\"" + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + " * " + attVal5_lucene + "\"";
		String regex_ngram4_quoted = attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
		String regex_ngram4_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal1) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		String luceneQuery4_flex = "\"" + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + "\"";
		String regex_ngram4_flex_quoted = attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted;
		//String regex_ngram4_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		// phrase consisting of 5 words before study title + fixed word behind
		String luceneQuery5 = "\"" + attVal0_lucene + " " + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + " * " + attVal5_lucene + "\"";
		String regex_ngram5_quoted = attVal0_quoted + "\\s" + attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted+ "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
		String regex_ngram5_normalizedAndQuoted = RegexUtils.normalizeAndEscapeRegex(attVal0) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal1) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		String luceneQuery5_flex = "\"" + attVal0_lucene + " " + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + "\"";
		String regex_ngram5_flex_quoted = attVal0_quoted + "\\s" + attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted;
		//String regex_ngram5_flex_normalizedAndQuoted = leftWords_regex.get(windowsize-5) + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
		
		List<String[]> ngramPats = new ArrayList<String[]>();
		// constraint for ngrams: at least one component not be a stopword
		
		// prevent induction of patterns less general than already known patterns:
		// check whether pattern is known before continuing
		// also improves performance
		if (this.processedPatterns.contains(regex_ngram1_normalizedAndQuoted)) { return ngramPats; } 
		if ( ! (isStopword(attVal4) & isStopword(attVal5)) & isRelevant(regex_ngram1_quoted, threshold))//0.2
		{
			// substitute normalized numbers etc. with regex
			String[] newPat = {luceneQuery1, regex_ngram1_normalizedAndQuoted };
			ngramPats.add(newPat); 
			System.out.println("found relevant type 1 pattern (most general): " + regex_ngram1_normalizedAndQuoted);
		}
		else 
		{
			//TODO: do not return here, instead process Type phrase behind study title terms also"
			if ( this.processedPatterns.contains( regex_ngram2_normalizedAndQuoted )) { return ngramPats; } 
			if ( !( isStopword( attVal4 ) & isStopword( attVal5 ) | isStopword( attVal3 ) & isStopword( attVal5 ) | isStopword( attVal3 ) & isStopword( attVal4) ) & isRelevant( regex_ngram2_quoted, threshold-0.02 ))//0.18
			{
				System.out.println( "found relevant type 2 pattern: " + regex_ngram2_normalizedAndQuoted );
				String[] newPat = { luceneQuery2, regex_ngram2_normalizedAndQuoted };
				ngramPats.add( newPat ); 
			}
			
			else
			{
				if (this.processedPatterns.contains( regex_ngram3_normalizedAndQuoted)) { return ngramPats; } 
				if ( !( isStopword( attVal2 ) & isStopword( attVal3 ) & isStopword( attVal4 ) & isStopword( attVal5 )) & isRelevant( regex_ngram3_quoted, threshold-0.04 ))//0.16
				{
					System.out.println("found relevant type 3 pattern: " + regex_ngram3_normalizedAndQuoted );
					//ngramPats.add(regex_ngram3_normalizedAndQuoted);
					String[] newPat = { luceneQuery3, regex_ngram3_normalizedAndQuoted };
					ngramPats.add( newPat ); 
				}
				else 
				{
					if ( this.processedPatterns.contains( regex_ngram4_normalizedAndQuoted )) {  return ngramPats; } 
					if ( !( isStopword( attVal1) & isStopword( attVal2 ) & isStopword( attVal3 ) & isStopword( attVal4 ) & isStopword( attVal5 )) & isRelevant( regex_ngram4_quoted, threshold-0.06 ))//0.14
					{
						System.out.println("found relevant type 4 pattern: " + regex_ngram4_normalizedAndQuoted );
						//ngramPats.add(regex_ngram4_normalizedAndQuoted);
						String[] newPat = { luceneQuery4, regex_ngram4_normalizedAndQuoted };
						ngramPats.add( newPat ); 
					}
					else
					{
						if ( this.processedPatterns.contains( regex_ngram5_normalizedAndQuoted )) { return ngramPats; } 
						if ( !( isStopword( attVal0 ) & isStopword( attVal1 ) & isStopword( attVal2 ) & isStopword( attVal3 ) & isStopword( attVal4 ) & isStopword( attVal5 )) & isRelevant( regex_ngram5_quoted, threshold-0.08 ))//0.12
						{
							System.out.println("found relevant type 5 pattern: " + regex_ngram5_normalizedAndQuoted );
							//ngramPats.add(regex_ngram5_normalizedAndQuoted);
							String[] newPat = { luceneQuery5, regex_ngram5_normalizedAndQuoted };
							ngramPats.add( newPat ); 
						}
					}
				}
			}
			if ( this.processedPatterns.contains( regex_ngramA_normalizedAndQuoted )) { return ngramPats; } 
			if ( !( isStopword( attVal5 ) & isStopword( attVal6 ) | isStopword( attVal4 ) & isStopword( attVal6 ) | isStopword( attVal4 ) & isStopword( attVal5 ) ) & isRelevant( regex_ngramA_quoted, threshold-0-02 ))//0.18
			{
				System.out.println( "found relevant type A pattern: " + regex_ngramA_normalizedAndQuoted );
				//ngramPats.add(regex_ngramA_normalizedAndQuoted);
				String[] newPat = { luceneQueryA, regex_ngramA_normalizedAndQuoted };
				ngramPats.add( newPat ); 
			}
			else
			{
				if ( this.processedPatterns.contains(regex_ngramB_normalizedAndQuoted )) { return ngramPats; } 
				if ( !( isStopword( attVal4 ) & isStopword(attVal5) & isStopword( attVal6 ) & isStopword( attVal7 )) & isRelevant( regex_ngramB_quoted, threshold-0.04 ))//0.16
				{
					System.out.println( "found relevant type B pattern: " + regex_ngramB_normalizedAndQuoted );
					//ngramPats.add(regex_ngramB_normalizedAndQuoted);
					String[] newPat = { luceneQueryB, regex_ngramB_normalizedAndQuoted };
					ngramPats.add( newPat ); 
				}
				else
				{
					if ( this.processedPatterns.contains( regex_ngramC_normalizedAndQuoted )) { return ngramPats; } 
					if ( !( isStopword( attVal4 ) & isStopword( attVal5 ) & isStopword( attVal6 ) & isStopword( attVal7 ) & isStopword( attVal8 )) & isRelevant( regex_ngramC_quoted, threshold-0.06 ))//0.14
					{
						System.out.println( "found relevant type C pattern: " + regex_ngramC_normalizedAndQuoted );
						//ngramPats.add(regex_ngramC_normalizedAndQuoted);
						String[] newPat = { luceneQueryC, regex_ngramC_normalizedAndQuoted };
						ngramPats.add( newPat ); 
					}
					else
					{
						if ( this.processedPatterns.contains( regex_ngramD_normalizedAndQuoted )) { return ngramPats; } 
						if ( !( isStopword( attVal4 ) & isStopword( attVal5 ) & isStopword( attVal6 ) & isStopword( attVal7 ) & isStopword( attVal8 ) & isStopword( attVal9 )) & isRelevant( regex_ngramD_quoted, threshold-0.08 ))//0.12
						{
							System.out.println( "found relevant type D pattern: " + regex_ngramD_normalizedAndQuoted );
							//ngramPats.add(regex_ngramD_normalizedAndQuoted);
							String[] newPat = { luceneQueryD, regex_ngramD_normalizedAndQuoted };
							ngramPats.add( newPat ); 
						}
					}
				}
			}
		}
		return ngramPats;
	}
	
	//TODO: DOCSTRING
	/**
	 * Computes reliability of extraction pattern newPat: if above threshold, saves newPat along with its 
	 * extracted contexts
	 * 
	 * @param newPat		the extraction pattern (...)
	 * @param threshold		reliability threshold
	 * @return				true if pattern is deemed reliable, false otherwise
	 */
	private boolean saveRelevantPatternsAndContexts(String[] newPat, double threshold) throws IOException, ParseException
	{
		try { 
			List<String[]> extractedContexts = getReliable_pattern(newPat, threshold); 
			
			if (extractedContexts != null)
			{
				this.reliablePatternsAndContexts.put(newPat[1],  extractedContexts);
				for (String[] studyNcontext : extractedContexts)
				{
					String studyName = studyNcontext[0];
					this.foundSeeds_iteration.add(studyName);
				}
				System.out.println("found relevant pattern: " + newPat[1]);
				return true;
			}
			return false;
		}
		catch (ParseException pe) { pe.printStackTrace(); throw new ParseException(); }
	}
	
	/**
	 * Generates extraction patterns, computes their reliability and saves contexts extracted by 
	 * reliable patterns 
	 * 
	 * @param filenames_arff	training files containing dataset references, basis for pattern generation
	 * @param threshold			threshold for pattern reliability
	 * @return			...
	 */
	@SuppressWarnings("unused")
	private void saveReliablePatternData(List<StudyContext> contexts, double threshold) throws IOException, ParseException
	{
		int n = 0;
		for(StudyContext context : contexts)
		{
	    	n++;
	    	System.out.println("Inducing relevant patterns for instance " + n + " of " + contexts.size() + " for " + " \"" + context.getTerm() + "\"");
	
			String attVal0 = context.getLeftWords().get(0); //l5
			String attVal1 = context.getLeftWords().get(1); //l4
			String attVal2 = context.getLeftWords().get(2); //l3
			String attVal3 = context.getLeftWords().get(3); //l2
			String attVal4 = context.getLeftWords().get(4); //l1
			String attVal5 = context.getRightWords().get(0); //r1
			String attVal6 = context.getRightWords().get(1); //r2
			String attVal7 = context.getRightWords().get(2); //r3
			String attVal8 = context.getRightWords().get(3); //r4
			String attVal9 = context.getRightWords().get(4); //r5
				
			//TODO: CONSTRUCT LUCENE QUERIES ONLY WHEN NEEDED (BELOW) 
			String attVal0_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal0);
			String attVal1_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal1);
			String attVal2_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal2);
			String attVal3_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal3);
			String attVal4_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal4);
			String attVal5_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal5);
			String attVal6_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal6);
			String attVal7_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal7);
			String attVal8_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal8);
			String attVal9_lucene = RegexUtils.normalizeAndEscapeRegex_lucene(attVal9);
			
			String attVal0_quoted = Pattern.quote(attVal0);
			String attVal1_quoted = Pattern.quote(attVal1);
			String attVal2_quoted = Pattern.quote(attVal2);
			String attVal3_quoted = Pattern.quote(attVal3);
			String attVal4_quoted = Pattern.quote(attVal4);
			String attVal5_quoted = Pattern.quote(attVal5);
			String attVal6_quoted = Pattern.quote(attVal6);
			String attVal7_quoted = Pattern.quote(attVal7);
			String attVal8_quoted = Pattern.quote(attVal8);
			String attVal9_quoted = Pattern.quote(attVal9);
					
			String attVal4_regex = RegexUtils.normalizeAndEscapeRegex(attVal4);
			String attVal5_regex = RegexUtils.normalizeAndEscapeRegex(attVal5);
				
			//...
			if (attVal4.matches(".*\\P{Punct}")) 
			{ 
				attVal4_quoted += "\\s"; 
				attVal4_regex += "\\s"; 
			}
			if (attVal5.matches("\\P{Punct}.*")) 
			{ 
				attVal5_quoted = "\\s" + attVal5_quoted; 
				attVal5_regex = "\\s" + attVal5_regex; 
			}
				
				
			// two words enclosing study name
			String luceneQuery1 = "\"" + attVal4_lucene + " * " + attVal5_lucene + "\"";
			String regex_ngram1_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngram1_minimal = attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex;
			
			// phrase consisting of 2 words behind study title + fixed word before
			String luceneQueryA = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + "\""; 
			String regex_ngramA_normalizedAndQuoted = RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngramA_minimal = attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6);
			
			// phrase consisting of 2 words behind study title + (any) word found in data before
			// (any word cause this pattern is induced each time for each different instance having this phrase...)
			// TODO needed?
//			String luceneQueryA_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + "\""; 
//			String regex_ngramA_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted; 
			//String regex_ngramA_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			// phrase consisting of 3 words behind study title + fixed word before
			String luceneQueryB = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + "\""; 
			String regex_ngramB_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngramB_minimal = attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7);
			// TODO needed?
//			String luceneQueryB_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + "\""; 
//			String regex_ngramB_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted;
			//String regex_ngramB_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			//phrase consisting of 4 words behind study title + fixed word before
			String luceneQueryC = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + "\""; 
			String regex_ngramC_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngramC_minimal	= attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8);
			
			// TODO needed?
//			String luceneQueryC_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + "\""; 
//			String regex_ngramC_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted + "\\s" + attVal8_quoted;
			//String regex_ngramC_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.lastWordRegex;
			
			//phrase consisting of 5 words behind study title + fixed word before
			String luceneQueryD = "\"" + attVal4_lucene + " * " + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + " " + attVal9_lucene + "\""; 
			String regex_ngramD_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal9);
			String regex_ngramD_minimal = attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal9);
			
			// now the pattern can emerge at other positions, too, and is counted here as relevant...
			// TODO needed?
//			String luceneQueryD_flex = "\"" + attVal5_lucene + " " + attVal6_lucene + " " + attVal7_lucene + " " + attVal8_lucene + " " + attVal9_lucene + "\""; 
//			String regex_ngramD_flex_quoted = attVal5_quoted + "\\s" + attVal6_quoted + "\\s" + attVal7_quoted + "\\s" + attVal8_quoted + "\\s" + attVal9_quoted;
			//String regex_ngramD_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal6) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal7) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal8) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal9);
				
			// phrase consisting of 2 words before study title + fixed word behind
			String luceneQuery2 = "\"" + attVal3_lucene + " " + attVal4_lucene  + " * " + attVal5_lucene + "\""; 
			// TODO needed?
//			String regex_ngram2_quoted = attVal3_quoted + "\\s" + attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
			String regex_ngram2_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngram2_minimal = RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex;
			
			// TODO needed?
//			String luceneQuery2_flex = "\"" + attVal3_lucene + " " + attVal4_lucene + "\""; 
//			String regex_ngram2_flex_quoted = attVal3_quoted + "\\s" + attVal4_quoted;
			//String regex_ngram2_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			// phrase consisting of 3 words before study title + fixed word behind
			String luceneQuery3 = "\"" + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + " * " + attVal5_lucene + "\""; 
			// TODO needed?
//			String regex_ngram3_quoted = attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
			String regex_ngram3_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngram3_minimal = RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex;
			
			// TODO needed?
//			String luceneQuery3_flex = "\"" + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + "\""; 
//			String regex_ngram3_flex_quoted = attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted;
			//String regex_ngram3_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			//phrase consisting of 4 words before study title + fixed word behind
			String luceneQuery4 = "\"" + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + " * " + attVal5_lucene + "\"";
			// TODO needed?
//			String regex_ngram4_quoted = attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
			String regex_ngram4_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal1) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngram4_minimal = RegexUtils.normalizeAndEscapeRegex(attVal1) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex;
			// TODO needed?
//			String luceneQuery4_flex = "\"" + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + "\"";
//			String regex_ngram4_flex_quoted = attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted;
			//String regex_ngram4_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
				
			// phrase consisting of 5 words before study title + fixed word behind
			String luceneQuery5 = "\"" + attVal0_lucene + " " + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + " * " + attVal5_lucene + "\"";
			// TODO needed?
//			String regex_ngram5_quoted = attVal0_quoted + "\\s" + attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted+ "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_quoted;
			String regex_ngram5_normalizedAndQuoted = RegexUtils.normalizeAndEscapeRegex(attVal0) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal1) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			String regex_ngram5_minimal = RegexUtils.normalizeAndEscapeRegex(attVal0) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal1) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal2) + "\\s" + RegexUtils.normalizeAndEscapeRegex(attVal3) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex;
			
			// TODO needed?
//			String luceneQuery5_flex = "\"" + attVal0_lucene + " " + attVal1_lucene + " " + attVal2_lucene + " " + attVal3_lucene + " " + attVal4_lucene + "\"";
//			String regex_ngram5_flex_quoted = attVal0_quoted + "\\s" + attVal1_quoted + "\\s" + attVal2_quoted + "\\s" + attVal3_quoted + "\\s" + attVal4_quoted;
			//String regex_ngram5_flex_normalizedAndQuoted = leftWords_regex.get(windowsize-5) + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + attVal5_regex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			// constraint for ngrams: at least one component not be a stopword
			//TODO: CHECK DOCSTRINGS: ORDER CORRECT?
			// first entry: luceneQuery; second entry: normalized and quoted version; third entry: minimal version (for reliability checks...)
			String[] newPat = new String[3];
			// prevent induction of patterns less general than already known patterns:
			// check whether pattern is known before continuing
			// also improves performance
			// use pmi scores that are already stored... only compute reliability again, max may have changed
			if (this.processedPatterns.contains(regex_ngram1_normalizedAndQuoted)) { continue; }
			newPat[0] = luceneQuery1;
			newPat[1] = regex_ngram1_normalizedAndQuoted;
			newPat[2] = regex_ngram1_minimal; 
			if (!(isStopword(attVal4) & isStopword(attVal5)))
			{
				if (saveRelevantPatternsAndContexts(newPat, threshold)) { this.reliablePatterns_iteration.add(newPat[1]); continue; }
			}
			//TODO: do not return here, instead process Type phrase behind study title terms also!
			if (this.processedPatterns.contains( regex_ngram2_normalizedAndQuoted)) { continue; } 
			newPat[0] = luceneQuery2;
			newPat[1] = regex_ngram2_normalizedAndQuoted;
			newPat[2] = regex_ngram2_minimal; 
			if (!( (isStopword(attVal4) & isStopword(attVal5)) | (isStopword(attVal3) & isStopword(attVal5)) | (isStopword(attVal3) & isStopword(attVal4))))
			{
				if (saveRelevantPatternsAndContexts (newPat, threshold)) { this.reliablePatterns_iteration.add(newPat[1]); continue; }
			}
				
			if (this.processedPatterns.contains(regex_ngram3_normalizedAndQuoted)) { continue; } 
			newPat[0] = luceneQuery3; 
			newPat[1] = regex_ngram3_normalizedAndQuoted;
			newPat[2] = regex_ngram3_minimal; 
			if (!(isStopword( attVal2 ) & isStopword( attVal3 ) & isStopword( attVal4 ) & isStopword( attVal5 )) )
			{
				if ( saveRelevantPatternsAndContexts ( newPat, threshold )) { this.reliablePatterns_iteration.add( newPat[1] ); continue; }
			}
			
			if ( this.processedPatterns.contains( regex_ngram4_normalizedAndQuoted )) {  continue; }
			newPat[0] = luceneQuery4;
			newPat[1] = regex_ngram4_normalizedAndQuoted;
			newPat[2] = regex_ngram4_minimal; 
			if ( !( isStopword( attVal1 ) & isStopword( attVal2 ) & isStopword( attVal3 ) & isStopword( attVal4 ) & isStopword( attVal5 )) )
			{
				if ( saveRelevantPatternsAndContexts ( newPat, threshold )) { this.reliablePatterns_iteration.add( newPat[1] ); continue; }
			}
			if ( this.processedPatterns.contains( regex_ngram5_normalizedAndQuoted )) { continue; }
			newPat[0] = luceneQuery5;
			newPat[1] = regex_ngram5_normalizedAndQuoted;
			newPat[2] = regex_ngram5_minimal; 
			if ( !( isStopword( attVal0 ) & isStopword( attVal1 ) & isStopword( attVal2 ) & isStopword( attVal3 ) & isStopword( attVal4 ) & isStopword( attVal5 )) )
			{
				if ( saveRelevantPatternsAndContexts ( newPat, threshold )) { this.reliablePatterns_iteration.add( newPat[1] ); continue; }
			}
			
			//...
			if ( this.processedPatterns.contains( regex_ngramA_normalizedAndQuoted )) { continue; }
			newPat[0] = luceneQueryA;
			newPat[1] = regex_ngramA_normalizedAndQuoted;
			newPat[2] = regex_ngramA_minimal; 
			if (!((isStopword( attVal5 ) & isStopword( attVal6 )) | (isStopword( attVal4 ) & isStopword( attVal6 )) | (isStopword( attVal4 ) & isStopword( attVal5 )))) 
			{
				if ( saveRelevantPatternsAndContexts ( newPat, threshold )) { this.reliablePatterns_iteration.add( newPat[1] ); continue; }
			}
			
			if ( this.processedPatterns.contains(regex_ngramB_normalizedAndQuoted )) { continue; } 
			newPat[0] = luceneQueryB;
			newPat[1] = regex_ngramB_normalizedAndQuoted;
			newPat[2] = regex_ngramB_minimal; 
			if ( !( isStopword( attVal4 ) & isStopword(attVal5) & isStopword( attVal6 ) & isStopword( attVal7 )) )
			{
				if ( saveRelevantPatternsAndContexts ( newPat, threshold )) { this.reliablePatterns_iteration.add( newPat[1] ); continue; }
			}
				
			if ( this.processedPatterns.contains( regex_ngramC_normalizedAndQuoted )) { continue; } 
			newPat[0] = luceneQueryC;
			newPat[1] = regex_ngramC_normalizedAndQuoted;
			newPat[2] = regex_ngramC_minimal; 
			if ( !( isStopword( attVal4 ) & isStopword( attVal5 ) & isStopword( attVal6 ) & isStopword( attVal7 ) & isStopword( attVal8 )) )
			{
				if ( saveRelevantPatternsAndContexts ( newPat, threshold )) { this.reliablePatterns_iteration.add( newPat[1] ); continue; }
			}
				
			if ( this.processedPatterns.contains( regex_ngramD_normalizedAndQuoted )) { continue; } 
			newPat[0] = luceneQueryD;
			newPat[1] = regex_ngramD_normalizedAndQuoted;
			newPat[2] = regex_ngramD_minimal; 
			if ( !( isStopword( attVal4 ) & isStopword( attVal5 ) & isStopword( attVal6 ) & isStopword( attVal7 ) & isStopword( attVal8 ) & isStopword( attVal9 )) )
			{
				if ( saveRelevantPatternsAndContexts ( newPat, threshold )) { this.reliablePatterns_iteration.add( newPat[1] ); continue; }
			}
	   	}
	}
	
	/**
	 * Returns the attributes of the instances in data as strings
	 * 
	 * @param data	the training examples
	 * @return		first list containing all sentences of positive training instances, second list containing all sentences of negative training instances
	 */
	private List<List<String>> getStrings( Instances data )
	{
		String studySubstitute = "<STUDYNAME> ";
		List<String> sentences_pos = new ArrayList<String>();
		List<String> sentences_neg = new ArrayList<String>();
		List<String> sentences;
		@SuppressWarnings("unchecked")
		Enumeration<Instance> instanceEnum = data.enumerateInstances();
    	while (instanceEnum.hasMoreElements())
    	{	
    		Instance curInstance = instanceEnum.nextElement();
    		String curClassVal = curInstance.stringValue(curInstance.classAttribute());

    		@SuppressWarnings("unchecked")
			Enumeration<Attribute> attributeEnum = data.enumerateAttributes();
    		String contextString = "";
    		if (curClassVal.equals(new String("True"))) { sentences = sentences_pos; }
    		else { sentences = sentences_neg; }
    		
    		while (attributeEnum.hasMoreElements())
    		{
    			Attribute curAtt = attributeEnum.nextElement();
    			String attVal = curInstance.stringValue(curAtt);
    			contextString += attVal + " ";
    			if (curAtt.index() == 4) { contextString += studySubstitute; }
    		}
    		sentences.add( contextString);
    	}
    	List<List<String>> resList = new ArrayList<List<String>>();
    	resList.add(sentences_pos);
    	resList.add(sentences_neg);
    	return resList;
	}
	
	//TODO: use safeMatching instead of m.find()!
	/**
	 * Returns whether regular expression <emph>pattern</emph> can be found in string <emph>text</emph>.
	 * 
	 * @param pattern	regular expression to be searched in <emph>text</emph>
	 * @param text		input string sequence to search <emph>pattern</emph> in
	 * @return			true, if <emph>pattern</emph> is found in <emph>text</emph>, false otherwise
	 */
	private int patternFound(String pattern, String text)
	{
		Pattern pat = Pattern.compile(pattern);
		Matcher m = pat.matcher(text);
		boolean patFound = m.find();
		if (patFound) { return 1; }
		else { return 0; }	
	}
	
	
	/**
	 * Determines whether a regular expression is suitable for extracting dataset references using a 
	 * frequency-based measure
	 * 
	 * @param regex			regex to be tested
	 * @param threshold		threshold for frequency-based relevance measure
	 * @param contextStrings	set of extracted contexts as strings
	 * @return				<emph>true</emph>, if regex is found to be relevant, <emph>false</emph> otherwise
	 */
	boolean isRelevant(String regex, List<String> contextStrings, double threshold)
	{
		System.out.println("Checking if pattern is relevant: " + regex);
		// compute score for similar to tf-idf...
		// count occurrences of regex in positive vs negative contexts...
		int count_pos = 0;
		int count_neg = 0;
		List<String> contexts_neg = new ArrayList<String>();
		for ( String context : contextStrings ) {	count_pos += patternFound( regex, context ); }
		// contexts neg always empty right now
		for ( String context : contexts_neg ) {	count_neg += patternFound( regex, context ); }
		
		//TODO: rename - this is not really tf-idf ;)
		double idf = 0;
		// compute relevance...
		if (count_neg + count_pos > 0) { 
			idf = log2( (double)( contextStrings.size() + contexts_neg.size() ) / ( count_neg + count_pos ) );
		}
		
		double tf_idf = ( (double)count_pos / contextStrings.size() ) * idf;
		if ( (tf_idf > threshold) & (count_pos > 1) ) {	return true; }
		else { return false; }
	}
	
	
	
	//TODO: use negative training examples to measure relevance?
	/**
	 * Determines whether a regular expression is suitable for extracting dataset references using a 
	 * frequency-based measure
	 * 
	 * @param ngramRegex	regex to be tested
	 * @param threshold		threshold for frequency-based relevance measure
	 * @return				<emph>true</emph>, if regex is found to be relevant, <emph>false</emph> otherwise
	 */
	private boolean isRelevant(String ngramRegex, double threshold)
	{
		System.out.println("Checking if pattern is relevant: " + ngramRegex);
		// compute score for similar to tf-idf...
		List<String> contexts_pos = this.contextsAsStrings.get(0);
		List<String> contexts_neg = this.contextsAsStrings.get(1);
		// count occurrences of ngram in positive vs negative contexts...
		int count_pos = 0;
		int count_neg = 0;
		for ( String context : contexts_pos ) {	count_pos += patternFound( ngramRegex, context ); }
		// contexts neg always empty right now
		for ( String context : contexts_neg ) {	count_neg += patternFound( ngramRegex, context ); }
		
		//TODO: rename - this is not really tf-idf ;)
		double idf = 0;
		// compute relevance...
		if (count_neg + count_pos > 0) 
		{ 
			idf = log2( (double)( contexts_pos.size() + contexts_neg.size() ) / ( count_neg + count_pos ) );
		}
		
		double tf_idf = ( (double)count_pos / contexts_pos.size() ) * idf;
		
		if ( tf_idf > threshold & count_pos > 1 ) {	return true; }
		else { return false; }
	}
	
	/**
	 * Computes the point-wise mutual information of two strings given their probabilities.
	 * see http://www.aclweb.org/anthology/P06-1#page=153
	 * @param p_xy	probability P(x,y), i.e. ...
	 * @param p_x 	probability P(x), i.e. ...
	 * @param p_y	probability P(y), i.e. ...
	 * @return
	 */
	public double pmi( double p_xy, double p_x, double p_y )
	{
		return log2( p_xy / (p_x * p_y ) );
	}
	
	/**
	 * Computes the reliability of an instance... see http://www.aclweb.org/anthology/P06-1#page=153
	 * 
	 * @return
	 */
	public double reliability(Reliability.Instance instance)
	{
		if (this.reliableInstances.contains(instance.name)) { return 1; }
		double rp = 0;
		Map<String, Double> patternsAndPmis = instance.associations;
		int P = patternsAndPmis.size();
		for (String patternString : patternsAndPmis.keySet())
		{
			double pmi = patternsAndPmis.get(patternString);
			Reliability.Pattern pattern = this.reliability.patterns.get(patternString);
			rp += ((pmi / this.reliability.maximumPmi) * reliability(pattern));
		}
		return rp / P;
	}
	
	/**
	 * Computes the reliability of a pattern... see http://www.aclweb.org/anthology/P06-1#page=153
	 * 
	 * @return
	 */
	public double reliability(Reliability.Pattern pattern)
	{
		double rp = 0;
		Map<String, Double> instancesAndPmis = pattern.associations;
		int P = instancesAndPmis.size();
		for (String instanceName : instancesAndPmis.keySet())
		{
			double pmi = instancesAndPmis.get(instanceName);
			Reliability.Instance instance = this.reliability.instances.get(instanceName);
			rp += ((pmi / this.reliability.maximumPmi) * reliability(instance));
		}
		return rp / P;
	}
	
	/**
	 * Determines reliability of pattern based on pattern ranking: if a pattern extracts many reliable 
	 * instances (dataset titles), it has a high reliability. Reliability of instance: extracted by many other patterns as 
	 * patterns as well = high agreement.
	 * 
	 * @param ngramRegex		the pattern to be assessed
	 * @return					list of extracted contexts of pattern if pattern reliablity score is above threshold, null else
	 **/
	//see: http://www.aclweb.org/anthology/P06-1#page=153 (cite here...)
	/*"Espresso ranks all patterns in P according to reliability rπ and discards all but the top-k, where
	 * k is set to the number of patterns from the previous iteration plus one. In general, we expect that
	 * the set of patterns is formed by those of the previous iteration plus a new one. Yet, new 
	 * statistical evidence can lead the algorithm to discard a	pattern that was previously discovered. "
	 */
	private List<String[]> getReliable_pattern(String[] ngramRegex, double threshold) throws IOException, ParseException
	{
		System.out.println("Checking if pattern is reliable: " + ngramRegex[1]);
		Reliability.Pattern newPat = this.reliability.new Pattern(ngramRegex[1]);
		// pattern hast to be searched in complete corpus to compute p_y
		//TODO: HOW DOES IT AFFECT PRECISION / RECALL IF KNOWN CONTEXT FILES ARE USED INSTEAD?
		//TODO: count occurrences of pattern in negative contexts and compute pmi etc...
		// count sentences or count only one occurrence per document?
		File corpus = new File(this.corpusPath);
		double data_size = corpus.list().length;
				
		// store results for later use (if pattern deemed reliable)
		Set<String[]> pattern = new HashSet<String[]>();
		pattern.add( ngramRegex );
		//
		try {
			List<String[]> extractedInfo_check = processPatterns_reliabilityCheck(pattern);
			//---
			//double p_y = extractedInfo.size() / data_size;
			// this yields the number of documents at least one occurrence of the pattern was found
			// multiple occurrences inside of one document are not considered
			//double p_y = matchingDocs.length / data_size;
			// this counts multiple occurrences inside of documents, not only document-wise
			double p_y = (double)extractedInfo_check.size() / data_size;
	
			// for every known instance, check whether pattern is associated with it
			for (String instance : this.reliableInstances)
			{
				int totalSentences = 0;
				int occurrencesPattern = 0;
				/*//Alternatively, read context xml file instead of arff file
				 //using arff file allows usage of positive vs. negative annotations though
				String instanceContext = this.contextPath + File.separator + Util.escapeSeed( instance ) + ".xml";
				TrainingSet trainingset_instance = new TrainingSet( new File( instanceContext ));
				Set<String[]> contexts = trainingset_instance.getContexts();*/

				//search patterns in all context sentences..-.
				List<StudyContext> instanceContexts = this.extractedContexts.get(instance);
				List<String> contexts_pos = getContextStrings(instanceContexts);
				//List<String> contexts_neg = this.contextsAsStrings[1];
				System.out.println("Searching for pattern " + ngramRegex[1] + " in contexts of " + instance);
				//TODO: USE SAFEMATCHING
				for (String context : contexts_pos)
				{
					totalSentences++;
					Pattern p = Pattern.compile(ngramRegex[1]);
					Matcher m = p.matcher(context);
					if (m.find()) { occurrencesPattern++; }
				}
	
				//double p_xy = occurrencesPattern / totalSentences;
				double p_xy = occurrencesPattern / data_size;
				// another way to count joint occurrences
			
				/*for ( String[] studyNcontext : extractedInfo )
				{
					String studyName = studyNcontext[0];
					String context = studyNcontext[1];
					String corpusFilename = studyNcontext[2];
					String usedPat = studyNcontext[3];
					context = Util.escapeXML(context);
					//replace all non-characters (utf-8) -> count ALLBUS 2000 and ALLBUS 2001 as instances of ALLBUS...
					String datasetSeries = studyName.replaceAll( "[^\\p{L}]", "" ).trim();
					datasetNames.add( datasetSeries );
					if ( jointOccurrences.containsKey( datasetSeries ))
					{
						jointOccurrences.put( datasetSeries, jointOccurrences.get( datasetSeries ) + 1 );
					}
					else { jointOccurrences.put( datasetSeries, 1 ); }
				}
				*/
				Reliability.Instance newInstance = this.reliability.new Instance(instance);
				//p_xy: P(x,y) - joint probability of pattern and instance ocurring in data 
				// all entries in the current context file belong to one instance (seed)
				// select those entries having the current pattern
	
				//additional searching step here is not necessary... change that
				//int jointOccurrences_xy = jointOccurrences.get( instance );
				//double p_xy = jointOccurrences_xy / data_size;
	
				//1. search for instance in the corpus
				//creates context xml files - if dataset is searched again as seed, saved file can be used
				//TrainingSet instanceContexts = searchForInstanceInCorpus( instance );
				//2. process context files
				//info needed: (1) contexts, (2) filenames (when counting occurrences per file)
				
				//Set<String[]> contexts = instanceContexts.getContexts();
				//Set<String> filenames = instanceContexts.getDocuments();
						
				//p_x: P(x) - probability of instance occurring in the data
				//number of times the instance occurs in the corpus
				//int totalOccurrences_x = contexts.size();
				//double p_x = totalOccurrences_x / data_size;
				double p_x = totalSentences / data_size;
				//p_x: P(y) - probability of pattern occurring in the data				
					
				System.out.println("Computing pmi of " + ngramRegex[1] + " and " + instance);
				double pmi_pattern = pmi(p_xy, p_x, p_y);
				newPat.addAssociation(instance, pmi_pattern);
				newInstance.addAssociation(newPat.pattern, pmi_pattern);
				
				//newInstance.setReliability( reliability_instance( instance ));
				// addPattern and addIstance take care of adding connections to 
				// consisting associations of entities
				this.reliability.addPattern(newPat);
				this.reliability.addInstance(newInstance);
				this.reliability.setMaxPmi(pmi_pattern);		
			}
			System.out.println("Computing relevance of " + ngramRegex[1]);
			double patternReliability = reliability(newPat);
			//newPat.setReliability( patternReliability );
			//this.reliability.addPattern( newPat );
			//double[] pmis, double[] instanceReliabilities, double max_pmi
			if (patternReliability >= threshold) 
			{ 
				System.out.println("Pattern " + ngramRegex[1] + " deemed reliable"); 
				//List<String[]> extractedInfo = processPatterns(pattern);
				List<String[]> extractedInfo = processPatterns(pattern);
				// number of found contexts = number of occurrences of patterns in the corpus
				// note: not per file though but in total
				// (with any arbitrary dataset title = instance)
				return extractedInfo; 
			}
			else { System.out.println("Pattern " + ngramRegex[1] + " deemed unreliable"); return null; }
		}
		catch(ParseException pe) { pe.printStackTrace(); throw new ParseException(); }
	}
	
			// TODO needed?
//	/**
//	 * Determines reliablity of instance based on instance ranking: if an instance is extracted by many 
//	 * reliable patterns, it has a high reliability. Reliability of pattern: extracts many reliable instances 
//	 * (in proportion to unreliable instances).
//	 * 
//	 * @param instance	the instance (dataset title) to be assessed
//	 * @return			boolean value: reliablity score above threshold or not
//	 */
//	private double reliability_instance( String instance )
//	{
//		System.out.println("Checking if instance is reliable: " + instance);
//		Reliability.Instance curInstance = this.reliability.instances.get(instance);
//		return reliability(curInstance);
//	}
	
	//TODO: ADD INSTANCE FILTERING FOR GENERIC PATTERNS (need to substitute 
	//google-based method there...)
	
	/**
	 * Checks whether a given word is a stop word
	 * 
	 * @param word	arbitrary string sequence to be checked
	 * @return		true if word is found to be a stop word, false otherwise
	 */
	private boolean isStopword(String word)
	{
		// word consists of punctuation, whitespace and digits only
		if (word.matches("[\\p{Punct}\\s\\d]*")) { return true; }
		// trim word, lower case and remove all punctuation
		word = word.replace("\\p{Punct}+", "").trim().toLowerCase();
		// due to text extraction errors, whitespace is frequently added to words resulting in many single characters
		// TODO: use this as small work-around but work on better methods for automatic text correction
		if (word.length() < 2) { return true; }
		if (RegexUtils.stopwordList().contains(word)) { return true; }
		// treat concatenations of stopwords as stopword
		for (String stopword : RegexUtils.stopwordList())
		{
			if (RegexUtils.stopwordList().contains(word.replace(stopword, "")))
			{ return true; }
		}
		return false; 
	}
	
	/**
	 * ...only difference to other processPatterns method: do not add processed patterns to set of processed patterns...
	 * 
	 * @param patSetIn		...
	 * @param seed			...
	 * @param outputDir		...
	 * @param path_index	...
	 * @param path_corpus	...
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private List<String[]> processPatterns_reliabilityCheck(Set<String[]> patSetIn) throws IOException, ParseException
	{
    	//TODO: DO THIS ONLY ONCE
    	String dir = this.corpusPath;
    	File corpus = new File(dir);
    	String[] corpus_test = corpus.list();
    	if (corpus_test == null) {
    	    // Either dir does not exist or is not a directory
    	} else {
    	    for (int i=0; i<corpus_test.length; i++) {
    	        // Get filename of file or directory
    	        corpus_test[i] = dir + File.separator + corpus_test[i];
    	    }
    	}
    	System.out.println("inserted all text filenames to corpus");
    	System.out.println("using patterns to extract new contexts...");
    	try { 
    		List<String[]> resNgrams = getStudyRefs_optimized_reliabilityCheck(patSetIn, corpus_test); 
	    	System.out.println("done. ");
	    	System.out.println( "Done processing patterns. ");
	
	    	return resNgrams;
    	}
    	catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
    	catch(ParseException pe) { pe.printStackTrace(); throw(new ParseException()); }
	}
	
	/**
	 * ...
	 * 
	 * @param patSetIn		...
	 * @param seed			...
	 * @param outputDir		...
	 * @param path_index	...
	 * @param path_corpus	...
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private List<String[]> processPatterns(Set<String[]> patSetIn) throws IOException, ParseException
	{
    	String dir = this.corpusPath;
    	File corpus = new File(dir);
    	String[] corpus_test = corpus.list();
    	if (corpus_test == null) {
    	    // Either dir does not exist or is not a directory
    	} else {
    	    for (int i=0; i<corpus_test.length; i++) {
    	        // Get filename of file or directory
    	        corpus_test[i] = dir + File.separator + corpus_test[i];
    	    }
    	}
    	System.out.println("inserted all text filenames to corpus");
    	System.out.println("using patterns to extract new contexts...");
    	try { 
    		List<String[]> resNgrams = getStudyRefs_optimized(patSetIn,corpus_test); 
	    	System.out.println("done. ");
	    	//outputContextFiles( resNgrams, "test", this.outputPath);
	    	System.out.println( "Done processing patterns. ");
	
	    	//TODO: add after output of results / usage of results, not here?
	    	for ( String[] p: patSetIn )
	    	{
	    		this.processedPatterns.add(p[1]);
	    	}
	    	return resNgrams;
    	}
    	catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
    	catch(ParseException pe) { pe.printStackTrace(); throw(new ParseException()); }
	}
	
	private Instances getInstances(String arffFilename) throws FileNotFoundException, IOException
	{
		Reader reader = new InputStreamReader(new FileInputStream(arffFilename), "UTF-8");
		Instances data = new Instances(reader);
		reader.close();
		// setting class attribute
		data.setClassIndex(data.numAttributes() - 1);
		return data;
	}

	/**
	 * Analyse given Instances and return relevant patterns.  
	 * 
	 * @param filename		location of the arff file containing the Instances to analyse
	 * @return				set of relevant patterns (each pattern consists of x elements: ... ...)
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private Set<String[]> induceRelevantPatternsFromArff(String filename, double threshold) throws FileNotFoundException, IOException
	{	
		Instances data = getInstances(filename);
		Instances data_positive = getInstances(data, "True");
		this.contextsAsStrings = getStrings(data);
		data_positive.setClassIndex(data_positive.numAttributes() - 1);
		System.out.println(data_positive.toSummaryString());
			
	    @SuppressWarnings("unchecked")
		Enumeration<Instance> posInstanceEnum = data_positive.enumerateInstances();
	    Set<String[]> patterns = new HashSet<String[]>();
	    int n = 0;
	    int m = data_positive.numInstances();
	    while (posInstanceEnum.hasMoreElements())
	    {
	    	Instance curInstance = posInstanceEnum.nextElement();
	    	n++;
	    	//save patterns and output...
	    	System.out.println("Inducing relevant patterns for instance " + n + " of " + m + " for " + " \"" + filename + "\"");
	    	patterns.addAll(getRelevantNgramPatterns(curInstance, data, threshold));
	    	System.out.println("Added all ngram-patterns for instance " + n + " of " + m + " to pattern set");
	    }
	    return patterns;
	}
	
	List<String> getContextStrings(List<StudyContext> contexts) {
		Function<StudyContext, String> context_toString = 
				new Function<StudyContext, String>() {
					public String apply(StudyContext c) { return c.toString(); }
		};
		return new ArrayList<String>(Lists.transform(contexts, context_toString));	
	}
	
	/**
	 * Analyse contexts and induce relevant patterns given the specified threshold.
	 * 
	 * @param contexts
	 * @param threshold
	 */
	@SuppressWarnings("unused")
	Set<String[]> inducePatterns(List<StudyContext> contexts, double threshold) {
		Set<String[]> patterns = new HashSet<String[]>();
		Set<String> processedPatterns_iteration = new HashSet<String>();
		List<String> allContextStrings_iteration = getContextStrings(contexts);
		
		for (StudyContext context : contexts) {

			List<String> leftWords = context.getLeftWords();
			List<String> rightWords = context.getRightWords();
			
			Function<String, String> normalizeAndEscape_lucene = 
					new Function<String, String>() {
						public String apply(String s) { return RegexUtils.normalizeAndEscapeRegex_lucene(s); }
			};
			
			Function<String, String> pattern_quote = 
					new Function<String, String>() {
						public String apply(String s) { return Pattern.quote(s); }
			};
			
			Function<String, String> regex_escape = 
					new Function<String, String>() {
						public String apply(String s) { return RegexUtils.normalizeAndEscapeRegex(s); }
			};
			//apply normalizeAndEscape_lucene method on all words of the context
			List<String> leftWords_lucene = new ArrayList<String>(Lists.transform(leftWords, normalizeAndEscape_lucene));
			List<String> rightWords_lucene = new ArrayList<String>(Lists.transform(rightWords, normalizeAndEscape_lucene));
			List<String> leftWords_quoted = new ArrayList<String>(Lists.transform(leftWords, pattern_quote));
			List<String> rightWords_quoted = new ArrayList<String>(Lists.transform(rightWords, pattern_quote));
			List<String> leftWords_regex = new ArrayList<String>(Lists.transform(leftWords, regex_escape));
			List<String> rightWords_regex = new ArrayList<String>(Lists.transform(rightWords, regex_escape));

			int windowSize = leftWords.size();
			String directNeighbourLeft = leftWords.get(windowSize-1);
			String directNeighbourRight = rightWords.get(0);
			
			//directly adjacent words may appear without being separated by whitespace iff those words consist of punctuation marks
			if (directNeighbourLeft.matches(".*\\P{Punct}")) 
			{ 
				leftWords_quoted.set(windowSize-1, leftWords_quoted.get(windowSize-1) + "\\s");
				leftWords_regex.set(windowSize-1, leftWords_regex.get(windowSize-1) + "\\s");
			}
			if (directNeighbourRight.matches("\\P{Punct}.*")) 
			{ 
				rightWords_quoted.set(0, "\\s" + rightWords_quoted.get(0));
				rightWords_regex.set(0, "\\s" + rightWords_regex.get(0)); 
			}
			
			// construct all allowed patterns
		
			//TODO: atomic regex...?
			// most general pattern: two words enclosing study name
			String luceneQuery1 = "\"" + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + "\"";
			String regex1_quoted = leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0);
			String regex1_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			// phrase consisting of 2 words behind study title + fixed word before
			String luceneQueryA = "\"" + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + "\""; 
			String regexA_quoted = regex1_quoted + "\\s" + rightWords_quoted.get(1);
			String regexA_normalizedAndQuoted = RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			// phrase consisting of 2 words behind study title + (any) word found in data before!
			// (any word cause this pattern is induced each time for each different instance having this phrase...)
			// TODO needed?
//			String luceneQueryA_flex = "\"" + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + "\""; 
//			String regexA_flex_quoted = rightWords_quoted.get(0) + "\\s" + rightWords_quoted.get(1); 
			//String regex_ngramA_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			// phrase consisting of 3 words behind study title + fixed word before
			String luceneQueryB = "\"" + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + " " + rightWords_lucene.get(2) + "\""; 
			String regexB_quoted = leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0) + "\\s" + rightWords_quoted.get(1) + "\\s" + rightWords_quoted.get(2);
			String regexB_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + rightWords_regex.get(2) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
						
			// TODO needed?
//			String luceneQueryB_flex = "\"" + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + " " + rightWords_lucene.get(2) + "\""; 
//			String regexB_flex_quoted = rightWords_quoted.get(0) + "\\s" + rightWords_quoted.get(1) + "\\s" + rightWords_quoted.get(2);
			//String regex_ngramB_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + rightWords_regex.get(2) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			// phrase consisting of 4 words behind study title + fixed word before
			String luceneQueryC = "\"" + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + " " + rightWords_lucene.get(2) + " " + rightWords_lucene.get(3) + "\""; 
			String regexC_quoted = leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0) + "\\s" + rightWords_quoted.get(1) + "\\s" + rightWords_quoted.get(2) + "\\s" + rightWords_quoted.get(3);
			String regexC_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + rightWords_regex.get(2) + "\\s" + rightWords_regex.get(3) + "\\s" + RegexUtils.lastWordRegex;
								
			String luceneQueryC_flex = "\"" + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + " " + rightWords_lucene.get(2) + " " + rightWords_lucene.get(3) + "\""; 
			String regexC_flex_quoted = rightWords_quoted.get(0) + "\\s" + rightWords_quoted.get(1) + "\\s" + rightWords_quoted.get(2) + "\\s" + rightWords_quoted.get(3);
			//String regex_ngramC_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + rightWords_regex.get(2) + "\\s" + rightWords_regex.get(3) + "\\s" + RegexUtils.lastWordRegex;
						
			// phrase consisting of 5 words behind study title + fixed word before
			String luceneQueryD = "\"" + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + " " + rightWords_lucene.get(2) + " " + rightWords_lucene.get(3) + " " + rightWords_lucene.get(4) + "\""; 
			String regexD_quoted = leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0) + "\\s" + rightWords_quoted.get(1) + "\\s" + rightWords_quoted.get(2) + "\\s" + rightWords_quoted.get(3) + "\\s" + rightWords_quoted.get(4);
			String regexD_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + rightWords_regex.get(2) + "\\s" + rightWords_regex.get(3) + "\\s" + rightWords_regex.get(4);

			// now the pattern can emerge at other positions, too, and is counted here as relevant...
			String luceneQueryD_flex = "\"" + rightWords_lucene.get(0) + " " + rightWords_lucene.get(1) + " " + rightWords_lucene.get(2) + " " + rightWords_lucene.get(3) + " " + rightWords_lucene.get(4) + "\""; 
			String regexD_flex_quoted = rightWords_quoted.get(0) + "\\s" + rightWords_quoted.get(1) + "\\s" + rightWords_quoted.get(2) + "\\s" + rightWords_quoted.get(3) + "\\s" + rightWords_quoted.get(4);
			//String regex_ngramD_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + rightWords_regex.get(1) + "\\s" + rightWords_regex.get(2) + "\\s" + rightWords_regex.get(3) + "\\s" + rightWords_regex.get(4);
					
			// phrase consisting of 2 words before study title + fixed word behind
			String luceneQuery2 = "\"" + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1)  + " * " + rightWords_lucene.get(0) + "\""; 
			String regex2_quoted = leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0);
			String regex2_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
						
			String luceneQuery2_flex = "\"" + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1) + "\""; 
			String regex2_flex_quoted = leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1);
			//String regex_ngram2_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
						
			// phrase consisting of 3 words before study title + fixed word behind
			String luceneQuery3 = "\"" + leftWords_lucene.get(windowSize-3) + " " + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + "\""; 
			String regex3_quoted = leftWords_quoted.get(windowSize-3) + "\\s" + leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0);
			String regex3_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
						
			String luceneQuery3_flex = "\"" + leftWords_lucene.get(windowSize-3) + " " + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1) + "\""; 
			String regex3_flex_quoted = leftWords_quoted.get(windowSize-3) + "\\s" + leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1);
			//String regex_ngram3_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
					
			// phrase consisting of 4 words before study title + fixed word behind
			String luceneQuery4 = "\"" + leftWords_lucene.get(windowSize-4) + " " + leftWords_lucene.get(windowSize-3) + " " + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + "\"";
			String regex4_quoted = leftWords_quoted.get(windowSize-4) + "\\s" + leftWords_quoted.get(windowSize-3) + "\\s" + leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0);
			String regex4_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
						
			String luceneQuery4_flex = "\"" + leftWords_lucene.get(windowSize-4) + " " + leftWords_lucene.get(windowSize-3) + " " + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1) + "\"";
			String regex4_flex_quoted = leftWords_quoted.get(windowSize-4) + "\\s" + leftWords_quoted.get(windowSize-3) + "\\s" + leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1);
			//String regex_ngram4_flex_normalizedAndQuoted = RegexUtils.wordRegex_atomic + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
					
			// phrase consisting of 5 words before study title + fixed word behind
			String luceneQuery5 = "\"" + leftWords_lucene.get(windowSize-5) + " " + leftWords_lucene.get(windowSize-4) + " " + leftWords_lucene.get(windowSize-3) + " " + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1) + " * " + rightWords_lucene.get(0) + "\"";
			String regex5_quoted = leftWords_quoted.get(windowSize-5) + "\\s" + leftWords_quoted.get(windowSize-4) + "\\s" + leftWords_quoted.get(windowSize-3) + "\\s" + leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_quoted.get(0);
			String regex5_normalizedAndQuoted = leftWords_regex.get(windowSize-5) + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + leftWords_regex.get(windowSize-1) + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
						
			String luceneQuery5_flex = "\"" + leftWords_lucene.get(windowSize-5) + " " + leftWords_lucene.get(windowSize-4) + " " + leftWords_lucene.get(windowSize-3) + " " + leftWords_lucene.get(windowSize-2) + " " + leftWords_lucene.get(windowSize-1) + "\"";
			String regex5_flex_quoted = leftWords_quoted.get(windowSize-5) + "\\s" + leftWords_quoted.get(windowSize-4) + "\\s" + leftWords_quoted.get(windowSize-3) + "\\s" + leftWords_quoted.get(windowSize-2) + "\\s" + leftWords_quoted.get(windowSize-1);
			//String regex_ngram5_flex_normalizedAndQuoted = leftWords_regex.get(windowSize-5) + "\\s" + leftWords_regex.get(windowSize-4) + "\\s" + leftWords_regex.get(windowSize-3) + "\\s" + leftWords_regex.get(windowSize-2) + "\\s" + attVal4_regex + "\\s?" + RegexUtils.studyRegex_ngram + "\\s?" + rightWords_regex.get(0) + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.wordRegex + "\\s" + RegexUtils.lastWordRegex;
			
			
			// constraint for patterns: at least one component not be a stopword
			// prevent induction of patterns less general than already known patterns:
			// check whether pattern is known before continuing
			// also improves performance
			//TODO: check for all patterns whether already found in current iteration
			if (this.processedPatterns.contains(regex1_normalizedAndQuoted) | processedPatterns_iteration.contains(regex1_normalizedAndQuoted)) { continue; } 
			if ( ! (isStopword(leftWords.get(windowSize-1)) & isStopword(rightWords.get(0))) & isRelevant(regex1_quoted, allContextStrings_iteration, threshold))//0.2
			{
				// substitute normalized numbers etc. with regex
				String[] newPat = {luceneQuery1, regex1_normalizedAndQuoted };
				patterns.add(newPat); processedPatterns_iteration.add(newPat[1]);
				System.out.println("found relevant type 1 pattern (most general): " + regex1_normalizedAndQuoted);
				continue;
			}
			//TODO: do not return here, instead process Type phrase behind study title terms also"
			if ( this.processedPatterns.contains( regex2_normalizedAndQuoted )) { continue; } 
			if ( !( isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(0) ) | isStopword( leftWords.get(windowSize-2) ) & isStopword( rightWords.get(0) ) | isStopword( leftWords.get(windowSize-2) ) & isStopword( leftWords.get(windowSize-1)) ) & isRelevant( regex2_quoted, allContextStrings_iteration, threshold-0.02 ))//0.18
			{
				System.out.println( "found relevant type 2 pattern: " + regex2_normalizedAndQuoted );
				String[] newPat = { luceneQuery2, regex2_normalizedAndQuoted };
				patterns.add( newPat ); 
				continue;
			}
			if (this.processedPatterns.contains( regex3_normalizedAndQuoted)) { continue; } 
			if ( !( isStopword( leftWords.get(windowSize-3) ) & isStopword( leftWords.get(windowSize-2) ) & isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(0) )) & isRelevant( regex3_quoted, allContextStrings_iteration, threshold-0.04 ))//0.16
			{
				System.out.println("found relevant type 3 pattern: " + regex3_normalizedAndQuoted );
				String[] newPat = { luceneQuery3, regex3_normalizedAndQuoted };
				patterns.add( newPat ); 
				continue;
			}
			if ( this.processedPatterns.contains( regex4_normalizedAndQuoted )) {  continue; } 
			if ( !( isStopword( leftWords.get(windowSize-4)) & isStopword( leftWords.get(windowSize-3) ) & isStopword( leftWords.get(windowSize-2) ) & isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(0) )) & isRelevant( regex4_quoted, allContextStrings_iteration, threshold-0.06 ))//0.14
			{
				System.out.println("found relevant type 4 pattern: " + regex4_normalizedAndQuoted );
				String[] newPat = { luceneQuery4, regex4_normalizedAndQuoted };
				patterns.add( newPat ); 
				continue;
			}
			if ( this.processedPatterns.contains( regex5_normalizedAndQuoted )) { continue; } 
			if ( !( isStopword( leftWords.get(windowSize-5) ) & isStopword( leftWords.get(windowSize-4) ) & isStopword( leftWords.get(windowSize-3) ) & isStopword( leftWords.get(windowSize-2) ) & isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(0) )) & isRelevant( regex5_quoted, allContextStrings_iteration, threshold-0.08 ))//0.12
			{
				System.out.println("found relevant type 5 pattern: " + regex5_normalizedAndQuoted );
				String[] newPat = { luceneQuery5, regex5_normalizedAndQuoted };
				patterns.add( newPat ); 
				continue;
			}
			
			if ( this.processedPatterns.contains( regexA_normalizedAndQuoted )) { continue; } 
			if ( !( isStopword( rightWords.get(0) ) & isStopword( rightWords.get(1) ) | isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(1) ) | isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(0) ) ) & isRelevant( regexA_quoted, allContextStrings_iteration, threshold-0-02 ))//0.18
			{
				System.out.println( "found relevant type A pattern: " + regexA_normalizedAndQuoted );
				String[] newPat = { luceneQueryA, regexA_normalizedAndQuoted };
				patterns.add( newPat ); 
				continue;
			}
			if ( this.processedPatterns.contains(regexB_normalizedAndQuoted )) { continue; } 
			if ( !( isStopword( leftWords.get(windowSize-1) ) & isStopword(rightWords.get(0)) & isStopword( rightWords.get(1) ) & isStopword( rightWords.get(2) )) & isRelevant( regexB_quoted, allContextStrings_iteration, threshold-0.04 ))//0.16
			{
				System.out.println( "found relevant type B pattern: " + regexB_normalizedAndQuoted );
				String[] newPat = { luceneQueryB, regexB_normalizedAndQuoted };
				patterns.add( newPat ); 
				continue;
			}
			if ( this.processedPatterns.contains( regexC_normalizedAndQuoted )) { continue; } 
			if ( !( isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(0) ) & isStopword( rightWords.get(1) ) & isStopword( rightWords.get(2) ) & isStopword( rightWords.get(3) )) & isRelevant( regexC_quoted, allContextStrings_iteration, threshold-0.06 ))//0.14
			{
				System.out.println( "found relevant type C pattern: " + regexC_normalizedAndQuoted );
				String[] newPat = { luceneQueryC, regexC_normalizedAndQuoted };
				patterns.add( newPat ); 
				continue;
			}
			if ( this.processedPatterns.contains( regexD_normalizedAndQuoted )) { continue; } 
			if ( !( isStopword( leftWords.get(windowSize-1) ) & isStopword( rightWords.get(0) ) & isStopword( rightWords.get(1) ) & isStopword( rightWords.get(2) ) & isStopword( rightWords.get(3) ) & isStopword( rightWords.get(4) )) & isRelevant( regexD_quoted, allContextStrings_iteration, threshold-0.08 ))//0.12
			{
				System.out.println( "found relevant type D pattern: " + regexD_normalizedAndQuoted );
				String[] newPat = { luceneQueryD, regexD_normalizedAndQuoted };
				patterns.add(newPat); processedPatterns_iteration.add(newPat[1]);
				continue;
			}
		}
		return patterns;
	}
	
	List<String[]> applyPatterns(Set<String[]> patterns) throws IOException, ParseException {
		File corpus = new File(this.corpusPath);
	    String[] corpus_test = corpus.list();
	    if (corpus_test == null) {
	        // Either dir does not exist or is not a directory
	    } else {
	        for (int i=0; i<corpus_test.length; i++) {
	            // Get filename of file or directory
	            corpus_test[i] = this.corpusPath + File.separator + corpus_test[i];
	        }
	    }
	    System.out.println("inserted all text filenames to corpus"); 
	    List<String[]> res = new ArrayList<String[]>();
		try { 
	    	res = getStudyRefs_optimized(patterns,corpus_test); 
	    }
	    catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
		catch(ParseException pe) { pe.printStackTrace(); throw(new ParseException()); }
		System.out.println("done applying patterns. ");
	    return res;
	}
	
	/**
	 * rest of deprecated and deleted method readArff - delete after having integrated the remaining functionality in calling methods.
	 * TODO has this been done?
	 * @param path_corpus
	 * @deprecated
	 */
	@SuppressWarnings("unused")
	private void readArff(String filename, String outputDir, double threshold) throws FileNotFoundException, IOException, ParseException
	{
		Set<String[]> ngramPats = induceRelevantPatternsFromArff(filename, threshold);
	    File corpus = new File(this.corpusPath);
	    String[] corpus_test = corpus.list();
	    if (corpus_test == null) {
	        // Either dir does not exist or is not a directory
	    } else {
	        for (int i=0; i<corpus_test.length; i++) {
	            // Get filename of file or directory
	            corpus_test[i] = this.corpusPath + File.separator + corpus_test[i];
	        }
	    }
	    System.out.println("inserted all text filenames to corpus");
	    	
	    String[] filenames_grams = new String[3];
	    filenames_grams[0] = outputDir + File.separator + new File(filename).getName().replace(".arff", "") + "_foundDatasets.csv";
	    filenames_grams[1] = outputDir + File.separator + new File(filename).getName().replace(".arff", "") + "_foundContexts.xml";
	    filenames_grams[2] = outputDir + File.separator + new File(filename).getName().replace(".arff", "") + "_usedPatterns.csv";
	    // before getting new refs, append all patterns to file
	    // note: all induced patterns, not only new ones
	    System.out.println("appending patterns to file...");
	    String allPatsFile = outputDir + File.separator + new File("newPatterns.txt");
	    OutputStreamWriter fstreamw = new OutputStreamWriter(new FileOutputStream(allPatsFile, true), "UTF-8");
		BufferedWriter outw = new BufferedWriter(fstreamw);
	    for (String p[] : ngramPats)
	    {
	    	outw.write(p[1] + System.getProperty("line.separator"));
	    }
	    outw.close();
	    System.out.println("done. ");
	    	
	    System.out.println("using patterns to extract new contexts...");
	    //TODO: use this instead?
	    //List<String[]> processPatterns(Set<String[]> patSetIn, String seed, String outputDir, String path_index, String path_corpus) throws FileNotFoundException, IOException
	    try { 
	    	List<String[]> resNgrams = getStudyRefs_optimized(ngramPats,corpus_test); 
	    	System.out.println("starting output of found studies and contexts (and used patterns)");
	    	output(resNgrams, filenames_grams); 
	    }
	    catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
	    catch(ParseException pe) { pe.printStackTrace(); throw(new ParseException()); }
	    //outputArffFile(filenames_grams[1]);
	    System.out.println("done. ");
	    	
	    System.out.println("writing patterns to file...");
	    String allNgramPatsFile = outputDir + File.separator + new File(filename).getName().replace(".arff", "") + "_foundPatterns_all.txt";
	    OutputStreamWriter fstream = new OutputStreamWriter(new FileOutputStream(allNgramPatsFile), "UTF-8");
		BufferedWriter outp = new BufferedWriter(fstream);
	    for (String p[] : ngramPats)
	    {
	    	outp.write(p[1] + System.getProperty("line.separator"));
	    }
	    outp.close();
	    System.out.println("done. ");
	}
	    
		/**
		 * Computes the logarithm (base 2) for a given value 
		 * 
		 * @param x	the value for which the log2 value is to be computed
		 * @return	the logarithm (base 2) for the given value
		 */
		private double log2( double x )
		{
		  return Math.log( x ) / Math.log( 2 );
		}
		
		/**
		 * Generates a regular expression to capture given <emph>title</emph> as dataset title along with 
		 * any number specifications.
		 * 
		 * @param title	name of the dataset to find inside the regex
		 * @return		a regular expression for finding the given title along with any number specifications
		 */
		public String constructTitleVersionRegex(String title)
		{
			// at least one whitespace required...
			return "(" + title + ")" + "\\S*?" + "\\s+" + "\\S*?" + "\\s*" + "\\S*?" + "\\s*" + "\\S*?" + "\\s*" +"\\S*?" + "\\s*" + "\\S*?" + "\\s*" + "((" + RegexUtils.yearRegex + "\\s*((-)|(–))\\s*\\d\\d(\\d\\d)?"+ ")|(" + RegexUtils.yearRegex + ")|(\\d+[.,-/\\\\]?\\d*))";
		}
		
		/**
		 * Generates regular expressions for finding dataset names listed in <emph>filename</emph> 
		 * with titles and number specifications.
		 * 
		 * @param filename	Name of the file containing a list of dataset names (one name per line)
		 * @return			A Set of Patterns
		 */
		public Set<Pattern> constructPatterns(String filename)
		{
			Set<Pattern> patternSet = new HashSet<Pattern>();
			try 
			{
				File f = new File(filename);
				InputStreamReader isr = new InputStreamReader(new FileInputStream(f), "UTF8");
				BufferedReader reader = new BufferedReader(isr);
				String studyTitle;
				while ((studyTitle = reader.readLine()) != null) 
				{
					if (! studyTitle.matches("\\s*")) 
					{ 
						patternSet.add(Pattern.compile(constructTitleVersionRegex(studyTitle))); 
					}
				}
				reader.close();
			}
			catch (IOException ioe) { ioe.printStackTrace(); return new HashSet<Pattern>(); }
			return patternSet;
		}
		
		
		//TODO: s. searchForPatterns getStudyRefs
		/**
		 * Searches for known dataset names...
		 * 
		 * @param patternSet	set of regular expressions (containing the names) to search for dataset references
		 * @param corpus		filenames of text documents to search for references
		 * @return				...
		 */
		public Map<String,Set<String[]>> getStudyRefs_unambiguous(Set<Pattern>patternSet, String[] corpus) throws IOException
		{
			Map<String,Set<String[]>> refList = new HashMap<String,Set<String[]>>();
			for (String filename: corpus)
			{
				System.out.println("searching for patterns in " + filename);
				char[] content = new char[(int) new File(filename).length()];
				Reader readerStream = new InputStreamReader(new FileInputStream(filename), "UTF-8");
				BufferedReader reader = new BufferedReader(readerStream);
				reader.read(content);
				reader.close();
				String input = new String(content);
				String inputClean = input.replaceAll("\\s+", " ");
				Iterator<Pattern> patIter = patternSet.iterator();
				Set<String[]> refs = new HashSet<String[]>();
				while (patIter.hasNext())
				{
					Pattern p = patIter.next();
					Matcher m = p.matcher(inputClean);
					System.out.println("Searching for pattern " + p);
					SafeMatching safeMatch = new SafeMatching(m);
					Thread thread = new Thread(safeMatch, filename + "\n" + p);
					long startTimeMillis = System.currentTimeMillis();
					// processing time for documents depends on size of the document. 1024 milliseconds allowed per KB
					long fileSize = new File(filename).length(); 
					long maxTimeMillis = fileSize;
					// set upper limit for processing time - prevents stack overflow caused by monitoring process (threadCompleted)
					//if ( maxTimeMillis > 750000 ) { maxTimeMillis = 750000; }
					if (maxTimeMillis > 75000) { maxTimeMillis = 75000; }
					thread.start();
					boolean matchFound = false;
					// if thread was aborted due to long processing time, matchFound should be false
					if (threadCompleted( thread, maxTimeMillis, startTimeMillis))
					{
						matchFound = safeMatch.find;
					}
					
					else
					{
						// TODO don't hardcode
						InfolisFileUtils.writeToFile(new File( "data/abortedMatches_studyTitles.txt" ), "utf-8", filename + ";" + p + "\n", true);
					}
					
					while (matchFound)
					{
						System.out.println("found pattern " + p + " in " + filename);
						String studyName = m.group(1).trim();
						String version = m.group(2).trim();
						System.out.println("version: " + version);
						System.out.println("studyName: " + studyName);
						String[] titleVersion = new String[2];
						titleVersion[0] = studyName;
						titleVersion[1] = version;
						refs.add( titleVersion);
						System.out.println("Searching for next match of pattern " + p);
						thread = new Thread(safeMatch, filename + "\n" + p);
						thread.start();
						matchFound = false;
						// if thread was aborted due to long processing time, matchFound should be false
						if (threadCompleted( thread, maxTimeMillis, startTimeMillis))
						{
							matchFound = safeMatch.find;
						}
						
						else
						{
							// TODO don't hardcode
							InfolisFileUtils.writeToFile(new File("data/abortedMatches_studyTitles.txt"), "utf-8", filename + ";" + p + "\n", true);
						}
						System.out.println("Processing new match...");
					}
				}
				Path path = Paths.get(filename);
				try { filename = basePath.relativize(path).normalize().toString(); }
				catch (IllegalArgumentException iae) { filename = basePath.normalize().toString(); }
				if (!refs.isEmpty()) { refList.put(filename, refs); }
			}
			return refList;
		}
		
		//TODO: error handling...
		/**
		 * Reads names of datasets from file, constructs regular expressions and searches them in specified 
		 * text corpus to extract dataset references.
		 * 
		 * @param path_output					name of path to save output files
		 * @param path_corpus					name of path to text corpus
		 * @param path_index					name of path to lucene index of text corpus
		 * @param path_knownTitles				name of file containing known and unambiguous dataset names
		 * @param filename_knownTitlesMentions	name of output file to save contexts of found dataset names
		 * @param constraint_NP					if set, only dataset names occuring inside a noun phrase are accepted
		 * @param constraint_upperCase			if set, only dataset names having at least one upper case character are accepted
		 */
		public static void searchForTerms(String path_output, String path_corpus, String path_index, String path_knownTitles, String filename_knownTitlesMentions, boolean constraint_upperCase, String taggingCmd, String chunkingCmd)
	{
		// list previously processed files to allow pausing and resuming of
		// testing operation
		Set<String> processedFiles = new HashSet<String>();
		try
		{
			File f = new File(path_output + File.separator + "processedDocs.csv");
			InputStreamReader isr = new InputStreamReader(new FileInputStream(f), "UTF8");
			BufferedReader reader = new BufferedReader(isr);
			String processedFile = null;
			while ((processedFile = reader.readLine()) != null)
			{
				if (!processedFile.matches("\\s*")) {
					processedFiles.add(processedFile);
				}
			}
			reader.close();
		} catch (IOException ioe) {
			System.err.println("warning: could not read processedDocs file. continuing... ");
		}
		File corpus = new File(path_corpus);
		String[] corpus_complete = corpus.list();
		Set<String> corpus_test_list = new HashSet<String>();
		for (int i = 0; i < corpus_complete.length; i++)
		{
			if (!processedFiles
					.contains(new File(path_corpus + File.separator + corpus_complete[i])
							.getAbsolutePath()))
			{
				corpus_test_list.add(new File(path_corpus + File.separator + corpus_complete[i])
						.getAbsolutePath());
			}
		}
		String[] corpus_test = new String[corpus_test_list.size()];
		corpus_test_list.toArray(corpus_test);
		System.out.println(corpus_test.length);
		System.out.println(processedFiles.size());
		System.out.println(corpus_complete.length);

		for (int i = 0; i < corpus_complete.length; i++)
		{
			corpus_complete[i] = new File(path_corpus + corpus_complete[i]).getAbsolutePath();
		}

		// need new Learner instance for each task - else, previously processed
		// patterns will not be processed again!
		Learner newLearner2 = new Learner(taggingCmd, chunkingCmd, constraint_upperCase,
				path_corpus, path_index, "", "", "", path_output);

		// get refs for known unambiguous studies
		// read study names from file
		// add study names to pattern
		Set<Pattern> patternSetKnown = newLearner2.constructPatterns(path_knownTitles);
		try {
			Map<String, Set<String[]>> resKnownStudies = newLearner2.getStudyRefs_unambiguous(
					patternSetKnown, corpus_complete);
			// write to file for use by contextMiner
			for (String f : resKnownStudies.keySet())
			{
				if (!resKnownStudies.get(f).isEmpty())
				{
					System.out.println(f);
					Set<String[]> titleVersionSet = resKnownStudies.get(f);
					for (String[] titleVersion : titleVersionSet)
						System.out.println(titleVersion[0] + " " + titleVersion[1]);
				}
			}

			File file = new File(path_output + File.separator + filename_knownTitlesMentions);
			FileOutputStream f = new FileOutputStream(file);
			ObjectOutputStream s = new ObjectOutputStream(f);
			s.writeObject(resKnownStudies);
			s.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
		
		//TODO: error handling...
		/**
		 * Reads existing patterns (regular expressions) from file and searches them in specified text
		 * corpus to extract dataset references.
		 * 
		 * @param path_patterns					name of file containing the patterns (regex)
		 * @param path_output					name of path to save output files
		 * @param path_corpus					name of path to text corpus
		 * @param path_index					name of path to lucene index of text corpus
		 * @param constraint_NP					if set, only dataset names occuring inside a noun phrase are accepted
		 * @param constraint_upperCase			if set, only dataset names having at least one upper case character are accepted
		 */
		public static void useExistingPatterns(String path_patterns, String path_output, String path_corpus, String path_index, boolean constraint_upperCase, String taggingCmd, String chunkingCmd)
		{
			// load saved patterns
			Set<String> patternSet1;
			try { patternSet1 = InfolisFileUtils.getDisctinctPatterns(new File(path_patterns)); }
			catch (IOException ioe) { patternSet1 = new HashSet<String>(); }

			// list previously processed files to allow pausing and resuming of testing operation
			Set<String> processedFiles = new HashSet<String>();
			try 
			{
				File f = new File(path_output + File.separator + "processedDocs.csv");
				InputStreamReader isr = new InputStreamReader(new FileInputStream(f), "UTF-8");
				BufferedReader reader = new BufferedReader(isr);
				String processedFile = null;
				while ((processedFile = reader.readLine()) != null) 
				{
					if (! processedFile.matches("\\s*")) { processedFiles.add(processedFile); }
				}
				reader.close();
			}
			catch (IOException ioe) { System.err.println("warning: could not read processedDocs file. continuing... "); }
		    File corpus = new File(path_corpus);
		    String[] corpus_complete = corpus.list();
		    Set<String> corpus_test_list = new HashSet<String>();
            for (int i=0; i<corpus_complete.length; i++) {
                if (!processedFiles.contains(new File(path_corpus + corpus_complete[i])
                                .getAbsolutePath()))
                {
                        corpus_test_list.add(new File(path_corpus + corpus_complete[i]).getAbsolutePath());
                }
            }
            String[] corpus_test = new String[corpus_test_list.size()];
            corpus_test_list.toArray(corpus_test);
            System.out.println(corpus_test.length);
            System.out.println(processedFiles.size());
            System.out.println(corpus_complete.length); 
                        
            for (int i=0; i<corpus_complete.length; i++) 
            { 
                corpus_complete[i] = new File(path_corpus + File.separator + corpus_complete[i]).getAbsolutePath(); 
            }
		    // need new Learner instance for each task - else, previously processed patterns will not be processed again
		    Learner newLearner = new Learner(taggingCmd, chunkingCmd, constraint_upperCase, path_corpus, path_index, "", "", "", path_output);
		    try {
			    List<String[]> resNgrams1 = newLearner.getStudyRefs(patternSet1,corpus_test,path_output);
			    String[] filenames_grams = new String[3];		
				filenames_grams[0] = path_output + File.separator + "datasets_patterns.csv";
			    filenames_grams[1] = path_output + File.separator + "contexts_patterns.xml";
			    filenames_grams[2] = path_output + File.separator + "patterns_patterns.csv"; 	
			    newLearner.output_distinct(resNgrams1, filenames_grams, false);	
		    }
		    catch(IOException ioe) { ioe.printStackTrace(); }
		}
		
		/**
		 * Bootraps patterns for identifying references to datasets from initial seed (known dataset name). 
		 * Pattern validity is assessed using frequency-based measure. 
		 * 
		 * @param initialSeed	initial term to be searched for as starting point of the algorithm
		 * @param path_index	name of the directory of the lucene index to be searched
		 * @param path_train	name of the directory containing the training files
		 * @param path_corpus	name of the directory containing the text corpus
		 * @param path_output	name of the directory containing the output files
		 * @param path_contexts	name of the directory containing the context files
		 * @param path_arffs	name of the directory containing the arff files
		 */
		public static void learn(Collection<String> seeds, String path_index, String path_train, String path_corpus, String path_output, String path_contexts, String path_arffs, boolean constraint_upperCase, String taggingCmd, String chunkingCmd, double threshold, int maxIterations, String strategy)
		{
			try
			{
				Learner learner = new Learner(taggingCmd, chunkingCmd, constraint_upperCase, path_corpus, path_index, path_train, path_contexts, path_arffs, path_output); 
				learner.outputParameterInfo(seeds, path_index, path_train, path_corpus, path_output, path_contexts, path_arffs, chunkingCmd != null, constraint_upperCase, "frequency_" + strategy, threshold, maxIterations);
				learner.reliableInstances.addAll(seeds);
				learner.bootstrap_frequency(seeds, -1, threshold, maxIterations, strategy);
			}
			catch (FileNotFoundException e) { System.err.println(e); }
			catch (IOException ioe) { System.err.println(ioe); }
			catch (ParseException pe) { pe.printStackTrace(); System.exit(1); }
		}
		
		/**
		 * Bootraps patterns for identifying references to datasets from initial set of seeds (known dataset names).
		 * This method uses pattern and instance ranking methods proposed by (cite Espresso paper here...)
		 * 
		 * @param initialSeeds	initial terms to be searched for as starting point of the algorithm
		 * @param path_index	name of the directory of the lucene index to be searched
		 * @param path_train	name of the directory containing the training files
		 * @param path_corpus	name of the directory containing the text corpus
		 * @param path_output	name of the directory containing the output files
		 * @param path_contexts	name of the directory containing the context files
		 * @param path_arffs	name of the directory containing the arff files
		 */
		public static void learn(Collection<String> initialSeeds, String path_index, String path_train, String path_corpus, String path_output, String path_contexts, String path_arffs, boolean constraint_upperCase, String taggingCmd, String chunkingCmd, double threshold, int maxIterations)
		{
			Learner learner = new Learner(taggingCmd, chunkingCmd, constraint_upperCase, path_corpus, path_index, path_train, path_contexts, path_arffs, path_output); 
			learner.outputParameterInfo(initialSeeds, path_index, path_train, path_corpus, path_output, path_contexts, path_arffs, chunkingCmd != null, constraint_upperCase, "reliability", threshold, maxIterations);
			learner.reliableInstances.addAll(initialSeeds);
			try {
				learner.bootstrap(initialSeeds, threshold, maxIterations);
				learner.outputReliableReferences(); 
			}
			catch(IOException ioe) { ioe.printStackTrace(); System.exit(1); }
			catch(ParseException pe) { pe.printStackTrace(); System.exit(1); }
		}
		
		public String getDateTime()
		{
			DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
			Date date = new Date();
			return dateFormat.format(date);
		}
		
		public void outputParameterInfo(Collection<String> initialSeeds, String path_index, String path_train, String path_corpus, String path_output, String path_contexts, String path_arffs, boolean constraint_NP, boolean constraint_upperCase, String method, double threshold, int maxIterations)
		{
			String delimiter = RegexUtils.delimiter_csv;
			String timestamp = getDateTime();
			File logFile = new File(path_output + File.separator + "parameterInfo.csv");
			String parameters = "initial_seeds" + delimiter + "index_path" + delimiter + "train_path" + delimiter + 
								"corpus_path" + delimiter + "output_path" + delimiter + "context_path" + delimiter + 
								"arff_path" + delimiter + "constraint_NP" + delimiter + "constraint_upperCase" + delimiter + 
								"method" + delimiter + "threshold" + delimiter + "maxIterations" + delimiter + "start_time" + 
								System.getProperty("line.separator");
			for(String seed : initialSeeds) { parameters += seed + RegexUtils.delimiter_internal; }
			//remove delimiter at the end of the seed list
			parameters = parameters.substring(0, parameters.length()-RegexUtils.delimiter_internal.length());
			parameters = parameters.trim() + delimiter + path_index + delimiter + path_train + delimiter + path_corpus + 
						delimiter + path_output + delimiter + path_contexts + delimiter + path_arffs + delimiter + 
						constraint_NP + delimiter + constraint_upperCase + delimiter + method + delimiter + threshold + 
						delimiter + maxIterations + delimiter + timestamp;
			try { InfolisFileUtils.writeToFile(logFile, "utf-8", parameters, false); }
			catch(IOException ioe) { ioe.printStackTrace(); System.out.println(parameters); }
		}
		
		/**
		 * Writes all extracted references of reliable patterns to xml context file at this output path
		 */
		public void outputReliableReferences() throws IOException
		{
			List<String[]> reliableContexts = new ArrayList<String[]>();
			for ( String pattern : this.reliablePatternsAndContexts.keySet() )
			{
				List<String[]> contexts = this.reliablePatternsAndContexts.get( pattern );
				reliableContexts.addAll(contexts);
				//see getString_reliablePatternOutput( Map<String, List<String[]>> patternsAndContexts, int iteration )
			}
			String[] filenames = new String[3];
			filenames[0] = this.outputPath + File.separator + "datasets.csv";
			filenames[1] = this.outputPath + File.separator + "contexts.xml";
			filenames[2] = this.outputPath + File.separator + "patterns.csv";
				/*String[] studyNcontext = new String[4];
					studyNcontext[0] = studyName;
					studyNcontext[1] = context;
					studyNcontext[2] = filenameIn;
					studyNcontext[3] = curPat;
					res.add( studyNcontext );*/
			try { output(reliableContexts, filenames ); }
			catch(IOException ioe) { ioe.printStackTrace(); throw(new IOException()); }
		}
		
		
		/**
		 * Monitors the given thread and stops it when it exceeds its time-to-live. 
		 * Calls itself until the thread ends after completing its task or after being stopped.
		 * 
		 * @param thread	the thread to be monitored
		 * @param maxProcessTimeMillis	the maximum time-to-live for thread
		 * @param startTimeMillis	thread's birthday :)
		 * @return	false, if thread was stopped prematurely; true if thread ended after completion of its task
		 */
		@SuppressWarnings("deprecation")
		public static boolean threadCompleted(Thread thread, long maxProcessTimeMillis, long startTimeMillis)
		{
			if (thread.isAlive())
			{
				long curProcessTime = System.currentTimeMillis() - startTimeMillis;
				System.out.println("Thread " + thread.getName() + " running for " + curProcessTime + " millis.");
				if (curProcessTime > maxProcessTimeMillis) 
				{ 
					System.out.println("Thread taking too long, aborting (" + thread.getName());
					thread.stop();
					return false;
				}
			}
			else { return true; }
			
			try { Thread.sleep(100); }
			catch (InterruptedException ie) {; }
			return threadCompleted(thread, maxProcessTimeMillis, startTimeMillis); 
		}
		
		/**
		 * Main method - calls <emph>OptionHandler</emph> to parse command line options and execute 
		 * Learner methods accordingly.
		 * 
		 * @param args
		 * @throws UnsupportedEncodingException
		 */
		public static void main(String[] args) throws UnsupportedEncodingException, IOException
		{ 
			new OptionHandler().doMain(args);
			System.out.println("Finished all tasks! Bye :)");
		}
	}

/**
* Class for processing command line options using args4j.
*
* @author katarina.boland@gesis.org; based on sample program by Kohsuke Kawaguchi (kk@kohsuke.org)
*/
class OptionHandler {

	@Option(name="-c",usage="extract references from this corpus", metaVar = "CORPUS_PATH", required = true)
    private String corpusPath;
	
	@Option(name="-i",usage="use this Lucene Index for documents in corpus", metaVar = "INDEX_PATH", required = true)
    private String indexPath;
	
    @Option(name="-l",usage="learn extraction patterns from corpus and save training data to this directory", metaVar = "TRAIN_PATH")
    private String trainPath;
    
    @Option(name="-s",usage="learn extraction patterns using these seeds", metaVar = "SEED", required = true)
    private String seeds;

    @Option(name="-p",usage="use existing extraction patterns listed in this file", metaVar = "PATTERNS_FILENAME")
    private String patternPath;
    
    @Option(name="-t",usage="apply term search for dataset names listed in this file", metaVar = "TERMS_FILENAME")
    private String termsPath;
    
    @Option(name="-o",usage="output to this directory", metaVar="OUTPUT_PATH", required = true)
    private String outputPath;

    @Option(name="-n",usage="if set, use NP constraint with the specified tree tagger arguments TAGGER_ARGS", metaVar="TAGGER_ARGS")
    private String taggerArgs = null;
    
    @Option(name="-u",usage="if set, use upper-case constraint", metaVar="CONSTRAINT_UC_FLAG")
    private boolean constraintUC = false;
    
    @Option(name="-f",usage="apply frequency-based pattern validation method using the specified threshold", metaVar="FREQUENCY_THRESHOLD")
    private String frequencyThreshold;
    
    @Option(name="-r",usage="apply reliability-based pattern validation method using the specified threshold", metaVar="RELIABILITY_THRESHOLD")
    private String reliabilityThreshold;
    
    @Option(name="-N",usage="sets the maximum number of iterations to MAX_ITERATIONS. If not set, defaults to 4.", metaVar="MAX_ITERATIONS")
    private String maxIterations;
    
    @Option(name="-F",usage="sets the strategy to use for processing new seeds within the frequency-based framework to FREQUENCY_STRATEGY. If not set, defaults to \"separate\"", metaVar="FREQUENCY_STRATEGY")
    private String strategy;
    
    // receives other command line parameters than options
    @Argument
    private List<String> arguments = new ArrayList<String>();

    /**
     * Parses all command line options and calls <emph>Learner</emph> methods accordingly.
     * 
     * @param args			
     * @throws IOException
     */
    public void doMain(String[] args) throws IOException {
        CmdLineParser parser = new CmdLineParser(this); 

        // parse the arguments.
        try {
            parser.parseArgument(args);
        } catch(CmdLineException e) {
            System.err.println("Learner [options...] arguments...");
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            return;
        }
        
        if(trainPath != null)
            System.out.println("trainPath is set to " + trainPath);
        
        if(patternPath != null)
            System.out.println("patternPath is set to " + patternPath);

        String termsOut = "";
        if(termsPath != null) {
            termsOut = new File(termsPath).getName() + "_foundMentions.map";
        }
        // access non-option arguments
        /*
        System.out.println("other arguments are:");
        for( String s : arguments )
            System.out.println(s);
        */
        String taggingCmd = null;
        String chunkingCmd = null;
        if(taggerArgs != null) {
        	String[] taggerArgList = taggerArgs.split(RegexUtils.delimiter_internal);
        	taggingCmd = taggerArgList[0];
        	chunkingCmd = taggerArgList[1];
        }
        int maxIter = 4;
        if (maxIterations != null) maxIter = Integer.valueOf(maxIterations);
        
        String frequencyStrategy = "separate";
        if(strategy != null) frequencyStrategy = strategy;
        
        // call Learner.learn method with appropriate options
		Set<String> pathSet = new HashSet<String>();
		File root = new File(corpusPath);
		
		//add all documents to corpus for pattern- and term-based search
		if(patternPath != null | termsPath != null)
		{
			for(File file : root.listFiles()) 
			{
				if(file.isDirectory())
				{	
					pathSet.add(file.getName());
					System.out.println("Added path " + file.getName() + " to set.");
				}
			}
			
			System.out.println("Added all documents to corpus.");

			for (String basePath : pathSet)
			{
				// create output path if not existent
				File op = Paths.get(outputPath + File.separator + basePath + File.separator).normalize().toFile();
				if(!op.exists()) { op.mkdirs(); System.out.println("Created directory " + op); }
				if(patternPath != null) { Learner.useExistingPatterns(patternPath, outputPath + File.separator + basePath + File.separator, corpusPath + File.separator + basePath + File.separator, indexPath + "_" + basePath, constraintUC, taggingCmd, chunkingCmd); }
				if(termsPath != null) { Learner.searchForTerms(outputPath + File.separator + basePath + File.separator, corpusPath + File.separator + basePath + File.separator, indexPath + "_" + basePath, termsPath, termsOut, constraintUC, taggingCmd, chunkingCmd); }
			}
		}
		//TODO: train path not needed anymore. Use flag
		if (trainPath != null)
		{
			// create training and output paths if not existent
			File tp_contexts = Paths.get(trainPath + File.separator + "contexts" + File.separator).normalize().toFile();
			File tp_arffs = Paths.get(trainPath + File.separator + "arffs" + File.separator).normalize().toFile();
			File op = Paths.get(outputPath + File.separator).normalize().toFile();
			if(! tp_contexts.exists()) { tp_contexts.mkdirs(); System.out.println("Created directory " + tp_contexts); }
			if(! tp_arffs.exists()) { tp_arffs.mkdirs(); System.out.println("Created directory " + tp_arffs); }
			if(! op.exists()) { op.mkdirs(); System.out.println("Created directory " + op); }
			String[] seedArray = seeds.split(RegexUtils.delimiter_internal);
			if(reliabilityThreshold != null)
			{	
			    double threshold = Double.parseDouble(reliabilityThreshold);
			    Learner.learn(Arrays.asList(seedArray), indexPath, trainPath, corpusPath, outputPath, trainPath + File.separator + "contexts/", trainPath + File.separator + "arffs/", constraintUC, taggingCmd, chunkingCmd, threshold, maxIter); 
			}
			if(frequencyThreshold != null)
			{ 
			    double threshold = Double.parseDouble(frequencyThreshold);
			    Learner.learn(Arrays.asList(seedArray), indexPath, trainPath, corpusPath, outputPath, trainPath + File.separator + "contexts/" , trainPath + File.separator + "arffs/", constraintUC, taggingCmd, chunkingCmd, threshold, maxIter, frequencyStrategy); }
		}
    }
}