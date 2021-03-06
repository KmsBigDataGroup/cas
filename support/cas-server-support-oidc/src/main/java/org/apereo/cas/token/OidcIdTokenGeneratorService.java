package org.apereo.cas.token;

import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.apereo.cas.OidcConstants;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.OidcRegisteredService;
import org.apereo.cas.support.oauth.OAuthConstants;
import org.apereo.cas.support.oauth.OAuthResponseTypes;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.apereo.cas.ticket.accesstoken.AccessToken;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.DigestUtils;
import org.apereo.cas.util.EncodingUtils;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.core.profile.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * This is {@link OidcIdTokenGeneratorService}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public class OidcIdTokenGeneratorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OidcIdTokenGeneratorService.class);

    @Autowired
    private CasConfigurationProperties casProperties;

    private final String issuer;
    private final int skew;
    private final OidcIdTokenSigningAndEncryptionService signingService;

    public OidcIdTokenGeneratorService(final String issuer,
                                       final int skew,
                                       final OidcIdTokenSigningAndEncryptionService signingService) {
        this.signingService = signingService;
        this.issuer = issuer;
        this.skew = skew;
    }

    /**
     * Generate string.
     *
     * @param request           the request
     * @param response          the response
     * @param accessTokenId     the access token id
     * @param timeout           the timeout
     * @param responseType      the response type
     * @param registeredService the registered service
     * @return the string
     * @throws Exception the exception
     */
    public String generate(final HttpServletRequest request,
                           final HttpServletResponse response,
                           final AccessToken accessTokenId,
                           final long timeout,
                           final OAuthResponseTypes responseType,
                           final OAuthRegisteredService registeredService) throws Exception {

        final OidcRegisteredService oidcRegisteredService = (OidcRegisteredService) registeredService;

        final J2EContext context = new J2EContext(request, response);
        final ProfileManager manager = new ProfileManager(context);
        final Optional<UserProfile> profile = manager.get(true);

        LOGGER.debug("Attempting to produce claims for the id token [{}]", accessTokenId);
        final JwtClaims claims = produceIdTokenClaims(request, accessTokenId, timeout,
                oidcRegisteredService, profile.get(), context, responseType);
        LOGGER.debug("Produce claims for the id token [{}] as [{}]", accessTokenId, claims);

        return this.signingService.encode(oidcRegisteredService, claims);
    }

    /**
     * Produce id token claims jwt claims.
     *
     * @param request       the request
     * @param accessTokenId the access token id
     * @param timeout       the timeout
     * @param service       the service
     * @param profile       the user profile
     * @param context       the context
     * @param responseType  the response type
     * @return the jwt claims
     */
    protected JwtClaims produceIdTokenClaims(final HttpServletRequest request,
                                             final AccessToken accessTokenId,
                                             final long timeout,
                                             final OidcRegisteredService service,
                                             final UserProfile profile,
                                             final J2EContext context,
                                             final OAuthResponseTypes responseType) {
        final Authentication authentication = accessTokenId.getAuthentication();
        final Principal principal = authentication.getPrincipal();

        final JwtClaims claims = new JwtClaims();
        claims.setJwtId(UUID.randomUUID().toString());
        claims.setIssuer(this.issuer);
        claims.setAudience(service.getClientId());

        final NumericDate expirationDate = NumericDate.now();
        expirationDate.addSeconds(timeout);
        claims.setExpirationTime(expirationDate);
        claims.setIssuedAtToNow();
        claims.setNotBeforeMinutesInThePast(this.skew);
        claims.setSubject(principal.getId());

        if (authentication.getAttributes().containsKey(casProperties.getAuthn().getMfa().getAuthenticationContextAttribute())) {
            final Collection<Object> val = CollectionUtils.toCollection(
                    authentication.getAttributes().get(casProperties.getAuthn().getMfa().getAuthenticationContextAttribute()));
            claims.setStringClaim(OidcConstants.ACR, val.iterator().next().toString());
        }
        if (authentication.getAttributes().containsKey(AuthenticationHandler.SUCCESSFUL_AUTHENTICATION_HANDLERS)) {
            final Collection<Object> val = CollectionUtils.toCollection(
                    authentication.getAttributes().get(AuthenticationHandler.SUCCESSFUL_AUTHENTICATION_HANDLERS));
            claims.setStringListClaim(OidcConstants.AMR, val.toArray(new String[]{}));
        }

        claims.setClaim(OAuthConstants.STATE, authentication.getAttributes().get(OAuthConstants.STATE));
        claims.setClaim(OAuthConstants.NONCE, authentication.getAttributes().get(OAuthConstants.NONCE));
        claims.setClaim(OidcConstants.CLAIM_AT_HASH, generateAccessTokenHash(accessTokenId, service));

        principal.getAttributes().entrySet().stream()
                .filter(entry -> casProperties.getAuthn().getOidc().getClaims().contains(entry.getKey()))
                .forEach(entry -> claims.setClaim(entry.getKey(), entry.getValue()));

        if (!claims.hasClaim(OidcConstants.CLAIM_PREFERRED_USERNAME)) {
            claims.setClaim(OidcConstants.CLAIM_PREFERRED_USERNAME, profile.getId());
        }

        return claims;
    }

    private String generateAccessTokenHash(final AccessToken accessTokenId,
                                           final OidcRegisteredService service) {
        final byte[] tokenBytes = accessTokenId.getId().getBytes();
        final String hashAlg;

        switch (signingService.getJsonWebKeySigningAlgorithm()) {
            case AlgorithmIdentifiers.RSA_USING_SHA512:
                hashAlg = MessageDigestAlgorithms.SHA_512;
                break;
            case AlgorithmIdentifiers.RSA_USING_SHA256:
            default:
                hashAlg = MessageDigestAlgorithms.SHA_256;
        }

        LOGGER.debug("Digesting access token hash via algorithm [{}]", hashAlg);
        final byte[] digested = DigestUtils.rawDigest(hashAlg, tokenBytes);
        final byte[] hashBytesLeftHalf = Arrays.copyOf(digested, digested.length / 2);
        return EncodingUtils.encodeBase64(hashBytesLeftHalf);
    }
}

