package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.PermissionResult;
import com.spaceai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReteRicercaStrumento —— ricerca HTML DuckDuckGo (gratuita, senza chiave API).
 * <p>
 * Corrisponde a space-ai  WebSearchTool，inRicercaottienitempo realeInformazione。
 * tramiteAnalizza DuckDuckGo HTML RicercaRisultatoRicercaRisultato。
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** DuckDuckGo HTML Ricerca（nonrichiede JavaScript） */
    private static final String DDG_URL = "https://html.duckduckgo.com/html/";
    private static final int MAX_RESULTS = 8;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Override
    public String name() {
        return "WebSearch";
    }

    @Override
    public String description() {
        return "Search the web using DuckDuckGo. Returns search results with titles, URLs and snippets. " +
                "Use this to find up-to-date information, documentation, or answers to questions.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "Search query string"
                    },
                    "maxResults": {
                      "type": "integer",
                      "description": "Maximum number of results to return (default: 8)"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String query = (String) input.getOrDefault("query", "");
        return "Searching: " + query;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "Error: query parameter is required";
        }

        int maxResults = MAX_RESULTS;
        if (input.containsKey("maxResults")) {
            maxResults = ((Number) input.get("maxResults")).intValue();
            maxResults = Math.max(1, Math.min(maxResults, 20));
        }

        try {
            String html = fetchSearchPage(query);
            return parseResults(html, maxResults);
        } catch (Exception e) {
            log.debug("Search failed: query={}", query, e);
            return "Error: Search failed - " + e.getMessage();
        }
    }

    /** Richiesta DuckDuckGo HTML Ricerca */
    private String fetchSearchPage(String query) throws IOException, InterruptedException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DDG_URL + "?q=" + encodedQuery))
                .header("User-Agent", "Mozilla/5.0 (compatible; SpaceAIJava/1.0)")
                .GET()
                .timeout(TIMEOUT)
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }

    /** analizza i risultati di ricerca dalla pagina HTML di DuckDuckGo */
    private String parseResults(String html, int maxResults) {
        StringBuilder sb = new StringBuilder();

        // DuckDuckGo HTML RicercaRisultatoformato：
        // <a class="result__a" href="...">Title</a>
        // <a class="result__snippet" href="...">Snippet</a>
        // oRisultatoin <div class="result results_links results_links_deep web-result">

        // Risultatocollegamentoetitolo
        Pattern resultPattern = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL);

        // riepilogo
        Pattern snippetPattern = Pattern.compile(
                "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>",
                Pattern.DOTALL);

        Matcher resultMatcher = resultPattern.matcher(html);
        Matcher snippetMatcher = snippetPattern.matcher(html);

        int count = 0;
        while (resultMatcher.find() && count < maxResults) {
            count++;
            String url = resultMatcher.group(1);
            String title = stripHtml(resultMatcher.group(2));

            // DuckDuckGo collegamentosìformato，reale URL
            if (url.contains("uddg=")) {
                try {
                    String decoded = java.net.URLDecoder.decode(
                            url.substring(url.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                    // troncaa & prima
                    int ampIdx = decoded.indexOf('&');
                    if (ampIdx > 0) decoded = decoded.substring(0, ampIdx);
                    url = decoded;
                } catch (Exception ignored) {}
            }

            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = stripHtml(snippetMatcher.group(1));
            }

            sb.append(count).append(". ").append(title).append("\n");
            sb.append("   URL: ").append(url).append("\n");
            if (!snippet.isBlank()) {
                sb.append("   ").append(snippet).append("\n");
            }
            sb.append("\n");
        }

        if (count == 0) {
            // tentaanalisiModalità
            return parseResultsFallback(html, maxResults);
        }

        return sb.toString();
    }

    /** analisi：collegamentoEstrai */
    private String parseResultsFallback(String html, int maxResults) {
        StringBuilder sb = new StringBuilder();

        // c'èEsternocollegamento
        Pattern linkPattern = Pattern.compile("<a[^>]+href=\"(https?://[^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher matcher = linkPattern.matcher(html);

        int count = 0;
        java.util.Set<String> seenUrls = new java.util.HashSet<>();

        while (matcher.find() && count < maxResults) {
            String url = matcher.group(1);
            String title = stripHtml(matcher.group(2));

            // salta DuckDuckGo collegamentoeripete
            if (url.contains("duckduckgo.com") || title.isBlank() || !seenUrls.add(url)) {
                continue;
            }

            count++;
            sb.append(count).append(". ").append(title).append("\n");
            sb.append("   URL: ").append(url).append("\n\n");
        }

        if (count == 0) {
            return "No results found. Try a different query.";
        }

        return sb.toString();
    }

    /** dividi HTML tag */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#x27;", "'")
                .replaceAll("&nbsp;", " ")
                .strip();
    }
}
