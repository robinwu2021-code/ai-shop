package ai.neargo.shop.printing.center.port;

import ai.neargo.shop.spi.printing.*;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintCredentialLookupPortImpl implements PrintCredentialLookupPort {
    private final PrnCredentialVersionMapper credentials;

    public PrintCredentialLookupPortImpl(PrnCredentialVersionMapper c) {
        credentials = c;
    }

    public EncryptedPrintCredential get(PrintScope s, String ref) {
        var c =
                credentials.selectOne(
                        PrintSupport.<PrnCredentialVersion>owned(s).eq("credential_ref", ref));
        require(c != null && !"REVOKED".equals(c.getStatus()), "print.credentialUnavailable");
        return new EncryptedPrintCredential(
                ref,
                c.getEncryptionKeyVersion(),
                Base64.getDecoder().decode(c.getCredentialCiphertext()));
    }
}
