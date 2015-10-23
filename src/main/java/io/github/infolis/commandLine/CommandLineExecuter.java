package io.github.infolis.commandLine;

import io.github.infolis.algorithm.Algorithm;
import io.github.infolis.algorithm.TextExtractorAlgorithm;
import io.github.infolis.datastore.DataStoreClient;
import io.github.infolis.datastore.DataStoreClientFactory;
import io.github.infolis.datastore.DataStoreStrategy;
import io.github.infolis.datastore.FileResolver;
import io.github.infolis.datastore.FileResolverFactory;
import io.github.infolis.model.Execution;
import io.github.infolis.model.entity.InfolisFile;
import io.github.infolis.util.SerializationUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;

import org.apache.commons.io.IOUtils;

/**
 *
 * @author domi
 */
public class CommandLineExecuter {
    
    static protected DataStoreClient dataStoreClient;
    static protected FileResolver fileResolver;

    public static void parseJson(Path jsonPath, Path outputDir) throws FileNotFoundException, ClassNotFoundException, NoSuchFieldException, IllegalAccessException, IOException {
        JsonReader reader = Json.createReader(Files.newBufferedReader(jsonPath));
        JsonObject o = reader.readObject();

        Execution e = new Execution();
        //TODO: any better solution? e.g. to indicate the strategy as method parameter?
        dataStoreClient = DataStoreClientFactory.create(DataStoreStrategy.TEMPORARY);
        fileResolver = FileResolverFactory.create(DataStoreStrategy.TEMPORARY);
        
        //iterate through the entries in the JSON file
        for (Entry<String, JsonValue> values : o.entrySet()) {
            switch (values.getValue().getValueType()) {
                case STRING:
                case NUMBER:
                case TRUE:
                case FALSE:    
                    //algorithm has to be handled as a special case since we need to find the class
                    if (values.getKey().equals("algorithm")) {
                        String algorithmName = values.getValue().toString();
                        algorithmName = algorithmName.replace("\"", "");
                        if (!algorithmName.startsWith("io.github.infolis.algorithm")) {
                            algorithmName += "io.github.infolis.algorithm." + algorithmName;
                        }
                        Class<? extends Algorithm> algoClass = (Class<? extends Algorithm>) Class.forName(algorithmName);
                        e.setAlgorithm(algoClass);
                        break;
                    }
                    //inputFiles need to be handled as a special case since we need to create the 
                    //files first and post them and convert them if necessary
                    if(values.getKey().equals("inputFiles")) {
                        String dir = values.getValue().toString().replace("\"", "");
                        List<String> fileUris = postFiles(dir,dataStoreClient,fileResolver);                        
                        e.setInputFiles(convertPDF(fileUris));
                        break;
                    }                    
                    //all other fields are just set
                    e.setProperty(values.getKey(), values.getValue().toString().replace("\"", ""));
                    break;
                //for arrays we first have to create a list    
                case ARRAY:
                    JsonArray array = (JsonArray) values.getValue();
                    List<String> listEntries = new ArrayList<>();
                    for (int i = 0; i < array.size(); i++) {
                        JsonString stringSeed = array.getJsonString(i);                        
                        listEntries.add(stringSeed.getString());
                    }
                    e.setProperty(values.getKey(), listEntries);
                    break;
			default:
				System.err.println("WARNING: Unhandled value type " + values.getValue().getValueType());
				break;
            }
        }       
        dataStoreClient.post(Execution.class, e);        
        e.instantiateAlgorithm(dataStoreClient, fileResolver).run();
    }
    
    private static List<String> convertPDF(List<String> uris) {
        List<String> txtUris = new ArrayList<>();
        for (String inputFileURI : uris) {
            InfolisFile inputFile = dataStoreClient.get(InfolisFile.class, inputFileURI);
            if (null == inputFile) {
                throw new RuntimeException("File was not registered with the data store: " + inputFileURI);
            }
            if (null == inputFile.getMediaType()) {
                throw new RuntimeException("File has no mediaType: " + inputFileURI);
            }
            // if the input file is not a text file
            if (!inputFile.getMediaType().startsWith("text/plain")) {
                // if the input file is a PDF file, convert it
                if (inputFile.getMediaType().startsWith("application/pdf")) {
                    Execution convertExec = new Execution();
                    convertExec.setAlgorithm(TextExtractorAlgorithm.class);
                    convertExec.setInputFiles(Arrays.asList(inputFile.getUri()));
                    // TODO wire this more efficiently so files are stored temporarily
                    Algorithm algo = convertExec.instantiateAlgorithm(dataStoreClient,fileResolver);
                    algo.run();
                    // Set the inputFile to the file we just created
                    InfolisFile convertedInputFile = dataStoreClient.get(InfolisFile.class, convertExec.getOutputFiles().get(0));
                    txtUris.add(convertedInputFile.getUri());
                } 
            }
            else {
                txtUris.add(inputFileURI);
            }
        }
        return txtUris;
    }

    public static List<String> postFiles(String dir, DataStoreClient dsc, FileResolver rs) throws IOException {
        List<String> uris = new ArrayList<>();
        File dirFile = new File(dir);
        for (File f : dirFile.listFiles()) {
            
            Path tempFile = Files.createTempFile("infolis-", ".pdf");
            InfolisFile inFile = new InfolisFile();

            FileInputStream inputStream = new FileInputStream(f.getAbsolutePath());

            int numberBytes = inputStream.available();
            byte pdfBytes[] = new byte[numberBytes];
            inputStream.read(pdfBytes);

            IOUtils.write(pdfBytes, Files.newOutputStream(tempFile));

            inFile.setFileName(tempFile.toString());
            inFile.setMd5(SerializationUtils.getHexMd5(pdfBytes));
            inFile.setMediaType("application/pdf");
            inFile.setFileStatus("AVAILABLE");

            try {
                OutputStream os = rs.openOutputStream(inFile);
                IOUtils.write(pdfBytes, os);
                os.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            dsc.post(InfolisFile.class, inFile);       
            uris.add(inFile.getUri());
            inputStream.close();
        }
        return uris;
    }
    
    public static void usage(String problem) {
    	System.out.println(String.format("%s <json-path> <output-dir>", CommandLineExecuter.class.getSimpleName()));
    	if (null != problem)
    		System.out.println(String.format("ERROR: %s", problem));
    	System.exit(1);
    }
    
    public static void main(String args[]) throws FileNotFoundException, ClassNotFoundException, NoSuchFieldException, IllegalAccessException, IOException {
    	if (args.length < 2)
    		usage("Not enough arguments");

    	Path jsonPath = Paths.get(args[0]);
    	if (! Files.exists(jsonPath) )
    		usage("JSON doesn't exist");

    	Path outputDir = Paths.get(args[1]);
    	if (Files.exists(outputDir)) {
    		System.err.println("WARNING: Output directory already exists, make sure it is empty.\nPress enter to continue or CTRL-C to exit");
    		System.in.read();
    	} else {
    		Files.createDirectories(outputDir);
    	}

		parseJson(jsonPath, outputDir);
    }
}
