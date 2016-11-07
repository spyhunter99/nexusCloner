/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.miloss.nexuscloner;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import static java.lang.System.out;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 *
 * @author alex.oree
 */
public class Main {

    static ArrayList<Container> failures = new ArrayList<>();

    static class Container {

        public Container() {
        }

        String url;
        String destinationPath;
        int size = -1;
        Exception ex;

        private Container(String link, String absolutePath) {
            url = link;
            destinationPath = absolutePath;
        }
    }

    static class Wrapper {

        public Wrapper() {
            data = new ArrayList<>();
        }

        public ArrayList<Container> data;
    }

    static class DownloadWorker implements Runnable {
        @Override
        public void run() {
            while (!filesToDownload.isEmpty()) {
                Container remove = filesToDownload.remove();
                if (remove != null) {
                    try {
                        File f = new File(remove.destinationPath);
                        if (f.exists() && f.length()>0) {
                            //skip it
                        } else {
                            System.out.println("[" + filesToDownload.size() + "] downloading " + remove.url + " to " + remove.destinationPath);
                            
                            f.getParentFile().mkdirs();

                            CloseableHttpClient client = HttpClients.createDefault();
                            HttpGet get = new HttpGet(remove.url);
                            if (cookie != null) {
                                get.addHeader("Cookie", cookie);
                            }
                            CloseableHttpResponse execute = client.execute(get);
                            HttpEntity entity = execute.getEntity();
                            System.out.println(execute.getStatusLine().getStatusCode() + ": " + execute.getStatusLine().getReasonPhrase());
                            FileOutputStream os = new FileOutputStream(f);
                            InputStream is = entity.getContent();
                            byte[] buf = new byte[8192];
                            int bytesread = 0, bytesBuffered = 0;
                            while ((bytesread = is.read(buf)) > -1) {
                                os.write(buf, 0, bytesread);
                                bytesBuffered += bytesread;
                                if (bytesBuffered > 1024 * 1024) { //flush after 1MB
                                    bytesBuffered = 0;
                                    os.flush();
                                }
                            }
                            os.flush();
                            os.close();
                            is.close();
                            //FileUtils.copyInputStreamToFile(entity.getContent(), f);
                            get.releaseConnection();
                        }
                    } catch (Exception ex) {
                        System.err.println("Download error!" + remove.url);
                        ex.printStackTrace();
                        remove.ex=ex;
                        failures.add(remove);
                    }
                }
            }
        }

    }

    static String sourceUrl = "";
    static String auth = null;
    static String cookie = null;
    static ConcurrentLinkedQueue<Container> filesToDownload = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) throws Exception {

        final long now = System.currentTimeMillis();
        Options opts = new Options();
        opts.addOption("url", true, "root url of the repository you want to clone");

        opts.addOption("delay", true, "wait time between downloads (throttling)");
        opts.addOption("index", false, "build the index, must run first");
        opts.addOption("download", false, "download");
        opts.addOption("cookie", false, "prompt for cookie token");
        opts.addOption("username", true, "");
        opts.addOption("password", true, "prompt for password");
        opts.addOption("threads", true, "threads for concurrent downloads");
        opts.addOption("help", false, "help");

        CommandLineParser parser = new DefaultParser();
        CommandLine parse = parser.parse(opts, args);

        initSsl();
        String url = parse.getOptionValue("url");
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        if (parse.hasOption("username") && parse.hasOption("password")) {
            System.out.println("Password: ");

            String password = new String(System.console().readPassword());
            auth = new String(Base64.encodeBase64(
                    (parse.getOptionValue("username") + ":" + password).getBytes()
            ));
        }
        if (parse.hasOption("cookie")) {
            System.out.println("Cookie: ");

            String password = new String(System.console().readPassword());
            cookie = password;
            System.out.println("using cookie " + cookie);
        }
        sourceUrl = url;
        File outputFolder = new File("./output");
        outputFolder.mkdirs();
        Kryo kryo = new Kryo();
        if (parse.hasOption("index")) {
            process(url, outputFolder);
            //store the index
            Iterator<Container> iterator = filesToDownload.iterator();
            ArrayList<Container> data = new ArrayList<>();
            while (iterator.hasNext()) {
                data.add(iterator.next());
            }

            Wrapper d = new Wrapper();
            d.data = data;
            // ...
            Output output = new Output(new FileOutputStream("index.bin"));

            kryo.writeObject(output, d);
            output.close();

        }
        if (parse.hasOption("download")) {
            if (filesToDownload.isEmpty()) {

                Input input = new Input(new FileInputStream("index.bin"));
                Wrapper someObject = kryo.readObject(input, Wrapper.class);
                input.close();
                filesToDownload.addAll(someObject.data);
            }
            System.out.println(filesToDownload.size() + " files to download");
            System.out.println(filesToDownload.size() + " files to download");
            System.out.println(filesToDownload.size() + " files to download");
            int threads = 4;
            if (parse.hasOption("threads")) {
                threads = Integer.parseInt(parse.getOptionValue("threads"));
            }
            for (int i = 0; i < threads; i++) {
                DownloadWorker dw = new DownloadWorker();
                new Thread(dw).start();
            }
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("processed in " + (System.currentTimeMillis() - now) + "ms");
                    System.out.println("Download failures: " + failures.size());
                    for (int i=0; i < failures.size(); i++){
                        System.out.println(failures.get(i).url + ": " + failures.get(i).ex.getMessage());
                    }
                }
            }));
        }

        System.out.println("processed in " + (System.currentTimeMillis() - now) + "ms");
    }

    private static boolean isDirectory(String link) {
        return (link.endsWith("/"));
    }

    private static void process(String url, File outputFolder) throws Exception {
        outputFolder.mkdirs();
        System.out.println("processing directory " + url);
        Document doc;
        if (auth != null) {
            doc = Jsoup.connect(url).header("Authorization", "Basic " + auth).get();
        } else if (cookie != null) {
            doc = Jsoup.connect(url).header("Cookie", cookie).get();
        } else {
            doc = Jsoup.connect(url).get();
        }

        for (Element e : doc.select("a")) {
            String link = e.attr("href");
            if (!ignore(link)) {
                if (isDirectory(link)) {
                    process(link,
                            outputFolder);
                } else {
                    //download the file
                    URL urlFile = new URL(link);
                    String relativePath = link.replace(sourceUrl, "");
                    File outputFile = new File(outputFolder.getAbsolutePath() + File.separator + relativePath);

                    filesToDownload.add(new Container(link, outputFile.getAbsolutePath()));
                    //FileUtils.copyURLToFile(urlFile, outputFile );
                }
                //System.out.println(link);
            }
        }

    }

    private static boolean ignore(String link) {
        if (link.endsWith("../")) {
            return true;
        }
        return false;
    }

    private List<String> getDirectoryListing(String nexusUrl) throws Exception {
        List<String> ret = new ArrayList<>();

        Document doc = Jsoup.connect(nexusUrl).get();
        for (Element file : doc.select("td a")) {
            System.out.println(file.attr("href"));
            if (!file.attr("href").equalsIgnoreCase("../")) {
                ret.add(file.attr("href"));
            }

        }

        return ret;
    }

    private static void initSsl() throws Exception {
        TrustManager[] trustall = new TrustManager[]{new X509TrustManager() {

            @Override
            public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                System.out.println("Trust no one");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                System.out.println("Trust no one");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                System.out.println("Trust no one");
                return null;
            }
        }};
        SSLContext sc = SSLContext.getInstance("SSL");

        sc.init(null, trustall, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    }
}
