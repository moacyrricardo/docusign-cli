package io.github.moacyrricardo.docusign.envelope;

import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.EnvelopesInformation;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.Signer;

import java.util.ArrayList;
import java.util.List;

/** Builders for SDK envelope models used in 006/007 unit tests. */
final class EnvelopeFixtures {

    private EnvelopeFixtures() {
    }

    static Envelope envelope(String id, String subject, String status) {
        Envelope e = new Envelope();
        e.setEnvelopeId(id);
        e.setEmailSubject(subject);
        e.setStatus(status);
        e.setSentDateTime("2026-06-01T10:00:00Z");
        e.setLastModifiedDateTime("2026-06-02T11:00:00Z");
        return e;
    }

    static Envelope withSigners(Envelope e, Signer... signers) {
        Recipients recipients = new Recipients();
        List<Signer> list = new ArrayList<>(List.of(signers));
        recipients.setSigners(list);
        e.setRecipients(recipients);
        return e;
    }

    static Signer signer(String name, String email, String status) {
        Signer s = new Signer();
        s.setName(name);
        s.setEmail(email);
        s.setStatus(status);
        s.setRoutingOrder("1");
        return s;
    }

    /** A signer with an explicit routing order (for 007 ordering tests). */
    static Signer signerWithOrder(String name, String email, String status, String order) {
        Signer s = signer(name, email, status);
        s.setRoutingOrder(order);
        return s;
    }

    /** A standalone {@link Recipients} carrying the given signers (007 --recipients). */
    static Recipients recipients(Signer... signers) {
        Recipients r = new Recipients();
        r.setSigners(new ArrayList<>(List.of(signers)));
        return r;
    }

    /** An envelope with all four 007 timestamps set; pass null to leave one absent. */
    static Envelope statusEnvelope(String id, String subject, String status,
                                   String created, String sent, String completed) {
        Envelope e = new Envelope();
        e.setEnvelopeId(id);
        e.setEmailSubject(subject);
        e.setStatus(status);
        e.setCreatedDateTime(created);
        e.setSentDateTime(sent);
        e.setCompletedDateTime(completed);
        return e;
    }

    static EnvelopesInformation page(String totalSetSize, String nextUri, Envelope... envelopes) {
        EnvelopesInformation info = new EnvelopesInformation();
        info.setEnvelopes(new ArrayList<>(List.of(envelopes)));
        info.setResultSetSize(String.valueOf(envelopes.length));
        info.setTotalSetSize(totalSetSize);
        info.setNextUri(nextUri);
        return info;
    }
}
