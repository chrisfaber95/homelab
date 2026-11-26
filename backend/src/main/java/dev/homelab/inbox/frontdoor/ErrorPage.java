package dev.homelab.inbox.frontdoor;

/** {@code page()} of {@code server.py}: the little HTML page of a refused form; every real page is TypeScript. */
public final class ErrorPage {

    private ErrorPage() {}

    public static String html(String title, String body) {
        return "<!doctype html><html lang=\"nl\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width, initial-scale=1\"><title>" + escape(title) + "</title>"
                + "<meta name=\"color-scheme\" content=\"dark\"><meta name=\"theme-color\" content=\"#04070c\">"
                + "<link rel=\"stylesheet\" href=\"/static/app.css\"></head><body>"
                + "<header class=\"top\"><a class=\"mark\" href=\"/\">homelab</a></header><main class=\"narrow\">"
                + body + "</main></body></html>";
    }

    public static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;");
    }
}
