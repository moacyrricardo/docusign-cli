package io.github.moacyrricardo.docusign.docusign;

/**
 * The DocuSign environment — the single enum used for both the OAuth host (JWT mint) and the REST
 * base path (spec 002 §7). 003 does not define a second {@code OAuthHost}.
 */
public enum Environment {

    DEMO("account-d.docusign.com", "https://demo.docusign.net/restapi"),
    PROD("account.docusign.com", "https://www.docusign.net/restapi");

    private final String oAuthBasePath;
    private final String restBasePath;

    Environment(String oAuthBasePath, String restBasePath) {
        this.oAuthBasePath = oAuthBasePath;
        this.restBasePath = restBasePath;
    }

    /** OAuth host used by 003 for the JWT mint (e.g. {@code account-d.docusign.com}). */
    public String oAuthBasePath() {
        return oAuthBasePath;
    }

    /** REST base path used when {@code base_uri} is unset (e.g. {@code https://demo.docusign.net/restapi}). */
    public String restBasePath() {
        return restBasePath;
    }
}
