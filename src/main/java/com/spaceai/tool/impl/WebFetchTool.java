package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ottieniStrumento —— Corrisponde a space-ai/src/tools/WebFetchTool。
 * <p>
 * ottieni il contenuto dell'URL specificato tramite HTTP GET, convertendo automaticamente l'HTML in testo semplice.
 * supportadimensioneLimitazione、Timeoute HTML→testoConversione。
 */
public class WebFetchTool implements Tool {

    /** MassimoRispostadimensione corpo: 100KB */
    private static final int MAX_BODY_SIZE = 100 * 1024;

    /** HTTP RichiestaTimeout */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** User-Agent identificatore */
    private static final String USER_AGENT = "SpaceAI-Java/0.1 (WebFetchTool)";

    @Override
    public String name() {
        return "WebFetch";
    }

    @Override
    public String description() {
        return """
            Fetch the content of a URL. Returns the page content as text. \
            HTML pages are automatically simplified to readable text. \
            Useful for reading documentation, API responses, or web pages. \
            Has a 100KB size limit and 30s timeout.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The URL to fetch (must start with http:// or https://)"
                },
                "maxLength": {
                  "type": "integer",
                  "description": "Maximum number of characters to return (default: 50000)"
                }
              },
              "required": ["url"]
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String url = (String) input.get("url");
        int maxLength = input.containsKey("maxLength")
                ? ((Number) input.get("maxLength")).intValue()
                : 50000;

        // URL validazione
        if (url == null || url.isBlank()) {
            return "Error: URL is required";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: URL must start with http:// or https://";
        }

        try {
            URI uri = URI.create(url);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode >= 400) {
                return "Error: HTTP " + statusCode + "\n" + truncate(body, 2000);
            }

            // controlladimensioneLimitazione
            if (body.length() > MAX_BODY_SIZE) {
                body = body.substring(0, MAX_BODY_SIZE);
            }

            // in base acontenutoTipoGestisce
            String contentType = response.headers().firstValue("Content-Type").orElse("text/plain");

            String result;
            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                result = htmlToText(body);
            } else {
                result = body;
            }

            // troncaaMassimolungo
            result = truncate(result, maxLength);

            StringBuilder sb = new StringBuilder();
            sb.append("URL: ").append(url).append("\n");
            sb.append("Status: ").append(statusCode).append("\n");
            sb.append("Content-Type: ").append(contentType).append("\n");
            sb.append("---\n");
            sb.append(result);

            return sb.toString();

        } catch (IllegalArgumentException e) {
            return "Error: Invalid URL: " + e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            return "Error: Request timed out after " + TIMEOUT.toSeconds() + " seconds";
        } catch (Exception e) {
            return "Error fetching URL: " + e.getMessage();
        }
    }

    /**
     *  HTML → testoConversione。
     * rimuove/stile，Conversionetagcometestoformato。
     */
    private String htmlToText(String html) {
        // rimuove script e style 
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", "");

        // rimuove HTML commento
        text = text.replaceAll("(?s)<!--.*?-->", "");

        // verràcomea capo
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|pre)>", "\n");
        text = text.replaceAll("(?i)<(p|div|h[1-6]|li|tr|blockquote|pre)[^>]*>", "\n");

        // verràcollegamentocome [text](url) formato
        Pattern linkPattern = Pattern.compile("<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
        Matcher linkMatcher = linkPattern.matcher(text);
        text = linkMatcher.replaceAll("[$2]($1)");

        // rimuovec'èrimanente HTML tag
        text = text.replaceAll("<[^>]+>", "");

        // Decodifica HTML 
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&apos;", "'");
        text = text.replace("&nbsp;", " ");
        // carattere
        java.util.regex.Pattern numEntity = java.util.regex.Pattern.compile("&#(\\d+);");
        java.util.regex.Matcher numMatcher = numEntity.matcher(text);
        text = numMatcher.replaceAll(mr -> {
            try {
                return String.valueOf((char) Integer.parseInt(mr.group(1)));
            } catch (Exception e) {
                return mr.group();
            }
        });

        // Compressionepiùvuoto（3consopravuotoCompressionecome2)
        text = text.replaceAll("\\n{3,}", "\n\n");
        // Compressioneinternopiùvuoto
        text = text.replaceAll("[ \\t]+", " ");

        return text.strip();
    }

    /** troncatestoaspecificatolungo */
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n...[truncated at " + maxLength + " chars]";
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String url = (String) input.getOrDefault("url", "");
        // tronca i troppo lunghi URL
        if (url.length() > 50) {
            url = url.substring(0, 47) + "...";
        }
        return "🌐 Fetching " + url;
    }
}
