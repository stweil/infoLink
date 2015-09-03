package io.github.infolis.infolink.datasetMatcher;

import io.github.infolis.model.entity.SearchResult;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 *
 * @author domi
 */
public class HTMLQueryService extends QueryService {

    private String target;
    private int maxNumber = 10;

    public HTMLQueryService() {
        super();
    }

    public HTMLQueryService(String target) {
        super(target);
    }

    public String adaptQuery(String solrQuery) {
        //only extract the title
        String title = solrQuery.split("\\?date")[0];
        title = title.replace("?q=title", "");
        String query = String.format("%s?title=%s&max=%s&lang=de", target, title, String.valueOf(maxNumber));
        return query;
    }

    @Override
    public List<SearchResult> executeQuery(String solrQuery) {
        try {
            URL url = new URL(adaptQuery(solrQuery));
            URLConnection connection = url.openConnection();
            connection.setRequestProperty("Accept-Charset", "UTF-8");
            //connection.setConnectTimeout(6000);
            System.out.println("Reading from url...");
            // make sure that all data is read
            byte[] resultBuff = new byte[0];
            byte[] buff = new byte[1024];
            int k = -1;
            while ((k = connection.getInputStream().read(buff, 0, buff.length)) > -1) {
                byte[] tbuff = new byte[resultBuff.length + k]; // temp buffer size = bytes already read + bytes last read
                System.arraycopy(resultBuff, 0, tbuff, 0, resultBuff.length); // copy previous bytes
                System.arraycopy(buff, 0, tbuff, resultBuff.length, k);  // copy current lot
                resultBuff = tbuff; // call the temp buffer as your result buff
            }
            String htmlPage = new String(resultBuff);   
            return parseHTML(htmlPage);

        } catch (MalformedURLException ex) {
            Logger.getLogger(HTMLQueryService.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(HTMLQueryService.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    /**
     * Parses the HTML output of the dara search function and returns a map with
     * dataset DOIs (keys) and names (values).
     *
     * @param html	dara HTML output
     * @return	a map containing dataset DOIs (keys) and dataset names (values)
     */
    public List<SearchResult> parseHTML(String html) {
        List<SearchResult> results = new ArrayList<>();
        Document doc = Jsoup.parseBodyFragment(html);
        Elements hitlist = doc.getElementsByTag("li");
        int i=0;
        for (Element hit : hitlist) {
            String title = "";
            String identifier = "";
            //TODO: search for tag "a" first to limit elements to search by attribute value?
            Elements names = hit.getElementsByAttributeValueMatching("href", "/dara/study/web_show?.*");
            Elements dois = hit.getElementsByAttributeValueContaining("href", "http://dx.doi.org");
            // each entry has exactly one name and one doi element
            //TODO: except for some datasets that are not registered but only referenced in dara!
            // e.g. "OECD Employment Outlook" -> no doi listed here -> ignored
            for (Element name : names) {
                title = name.text().trim();
            }
            for (Element doi : dois) {
                identifier = doi.text().trim();
            }
            String num = getNumericInfo(title);
            SearchResult sr = new SearchResult();
            sr.setIdentifier(identifier);
            sr.setTitles(new ArrayList<>(Arrays.asList(title)));
            sr.setNumericInformation(new ArrayList<>(Arrays.asList(num)));
            sr.setListIndex(i);
            i++;
            results.add(sr);
        }
        return results;
    }

    /**
     * @return the maxNumber
     */
    public int getMaxNumber() {
        return maxNumber;
    }

    /**
     * @param maxNumber the maxNumber to set
     */
    public void setMaxNumber(int maxNumber) {
        this.maxNumber = maxNumber;
    }
}
