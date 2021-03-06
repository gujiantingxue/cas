package org.apereo.cas.authentication;

import org.apereo.cas.authentication.credential.UsernamePasswordCredential;
import org.apereo.cas.authentication.handler.support.AbstractUsernamePasswordAuthenticationHandler;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.CollectionUtils;

import com.google.common.collect.Maps;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.ReturnAttributes;
import org.ldaptive.auth.AuthenticationRequest;
import org.ldaptive.auth.AuthenticationResponse;
import org.ldaptive.auth.AuthenticationResultCode;
import org.ldaptive.auth.Authenticator;

import javax.security.auth.login.AccountNotFoundException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * LDAP authentication handler that uses the ldaptive {@code Authenticator} component underneath.
 * This handler provides simple attribute resolution machinery by reading attributes from the entry
 * corresponding to the DN of the bound user (in the bound security context) upon successful authentication.
 *
 * @author Marvin S. Addison
 * @since 4.0.0
 */
@Slf4j
@Setter
@Getter
public class LdapAuthenticationHandler extends AbstractUsernamePasswordAuthenticationHandler {

    /**
     * Mapping of LDAP attribute name to principal attribute name.
     */
    protected Map<String, Object> principalAttributeMap = new HashMap<>();

    /**
     * Performs LDAP authentication given username/password.
     **/
    private final Authenticator authenticator;
    /**
     * Name of attribute to be used for resolved principal.
     */
    private String principalIdAttribute;

    /**
     * Flag indicating whether multiple values are allowed fo principalIdAttribute.
     */
    private boolean allowMultiplePrincipalAttributeValues;

    /**
     * Flag to indicate whether CAS should block authentication
     * if a specific/configured principal id attribute is not found.
     */
    private boolean allowMissingPrincipalAttributeValue = true;

    /**
     * Set of LDAP attributes fetch from an entry as part of the authentication process.
     */
    private String[] authenticatedEntryAttributes = ReturnAttributes.NONE.value();

    private boolean collectDnAttribute;

    /**
     * Name of attribute to be used for principal's DN.
     */
    private String principalDnAttributeName = "principalLdapDn";

    /**
     * Creates a new authentication handler that delegates to the given authenticator.
     *
     * @param name             the name
     * @param servicesManager  the services manager
     * @param principalFactory the principal factory
     * @param order            the order
     * @param authenticator    Ldaptive authenticator component.
     * @param strategy         the strategy
     */
    public LdapAuthenticationHandler(final String name, final ServicesManager servicesManager,
                                     final PrincipalFactory principalFactory, final Integer order,
                                     final Authenticator authenticator,
                                     final AuthenticationPasswordPolicyHandlingStrategy strategy) {
        super(name, servicesManager, principalFactory, order);
        this.authenticator = authenticator;
        this.passwordPolicyHandlingStrategy = strategy;
    }

    @Override
    protected AuthenticationHandlerExecutionResult authenticateUsernamePasswordInternal(final UsernamePasswordCredential upc,
                                                                                        final String originalPassword) throws GeneralSecurityException, PreventedException {
        val response = getLdapAuthenticationResponse(upc);
        LOGGER.debug("LDAP response: [{}]", response);
        if (!passwordPolicyHandlingStrategy.supports(response)) {
            LOGGER.warn("Authentication has failed because LDAP password policy handling strategy [{}] cannot handle [{}].",
                response, passwordPolicyHandlingStrategy.getClass().getSimpleName());
            throw new FailedLoginException("Invalid credentials");
        }
        LOGGER.debug("Attempting to examine and handle LDAP password policy via [{}]",
            passwordPolicyHandlingStrategy.getClass().getSimpleName());
        val messageList = passwordPolicyHandlingStrategy.handle(response, getPasswordPolicyConfiguration());
        if (response.getResult()) {
            LOGGER.debug("LDAP response returned a result. Creating the final LDAP principal");
            val principal = createPrincipal(upc.getUsername(), response.getLdapEntry());
            return createHandlerResult(upc, principal, messageList);
        }
        if (AuthenticationResultCode.DN_RESOLUTION_FAILURE == response.getAuthenticationResultCode()) {
            LOGGER.warn("DN resolution failed. [{}]", response.getMessage());
            throw new AccountNotFoundException(upc.getUsername() + " not found.");
        }
        throw new FailedLoginException("Invalid credentials");
    }

    private AuthenticationResponse getLdapAuthenticationResponse(final UsernamePasswordCredential upc) throws PreventedException {
        try {
            LOGGER.debug("Attempting LDAP authentication for [{}]. Authenticator pre-configured attributes are [{}], "
                    + "additional requested attributes for this authentication request are [{}]", upc, authenticator.getReturnAttributes(),
                authenticatedEntryAttributes);
            val request = new AuthenticationRequest(upc.getUsername(), new org.ldaptive.Credential(upc.getPassword()), authenticatedEntryAttributes);
            return authenticator.authenticate(request);
        } catch (final LdapException e) {
            LOGGER.trace(e.getMessage(), e);
            throw new PreventedException("Unexpected LDAP error", e);
        }
    }

    /**
     * Creates a CAS principal with attributes if the LDAP entry contains principal attributes.
     *
     * @param username  Username that was successfully authenticated which is used for principal ID when principal id is not specified.
     * @param ldapEntry LDAP entry that may contain principal attributes.
     * @return Principal if the LDAP entry contains at least a principal ID attribute value, null otherwise.
     * @throws LoginException On security policy errors related to principal creation.
     */
    protected Principal createPrincipal(final String username, final LdapEntry ldapEntry) throws LoginException {
        LOGGER.debug("Creating LDAP principal for [{}] based on [{}] and attributes [{}]", username, ldapEntry.getDn(),
            ldapEntry.getAttributeNames());
        val id = getLdapPrincipalIdentifier(username, ldapEntry);
        LOGGER.debug("LDAP principal identifier created is [{}]", id);
        val attributeMap = collectAttributesForLdapEntry(ldapEntry, id);
        LOGGER.debug("Created LDAP principal for id [{}] and [{}] attributes", id, attributeMap.size());
        return this.principalFactory.createPrincipal(id, attributeMap);
    }

    /**
     * Collect attributes for ldap entry.
     *
     * @param ldapEntry the ldap entry
     * @param username  the username
     * @return the map
     */
    protected Map<String, List<Object>> collectAttributesForLdapEntry(final LdapEntry ldapEntry, final String username) {
        val attributeMap = Maps.<String, List<Object>>newHashMapWithExpectedSize(this.principalAttributeMap.size());
        LOGGER.debug("The following attributes are requested to be retrieved and mapped: [{}]", attributeMap.keySet());
        this.principalAttributeMap.forEach((key, attributeNames) -> {
            val attr = ldapEntry.getAttribute(key);
            if (attr != null) {
                LOGGER.debug("Found principal attribute: [{}]", attr);
                val names = (Collection<String>) attributeNames;
                if (names.isEmpty()) {
                    LOGGER.debug("Principal attribute [{}] is collected as [{}]", attr, key);
                    attributeMap.put(key, CollectionUtils.wrap(attr.getStringValues()));
                } else {
                    names.forEach(s -> {
                        LOGGER.debug("Principal attribute [{}] is virtually remapped/renamed to [{}]", attr, s);
                        attributeMap.put(s, CollectionUtils.wrap(attr.getStringValues()));
                    });
                }
            } else {
                LOGGER.warn("Requested LDAP attribute [{}] could not be found on the resolved LDAP entry for [{}]", key, ldapEntry.getDn());
            }
        });
        if (this.collectDnAttribute) {
            LOGGER.debug("Recording principal DN attribute as [{}]", this.principalDnAttributeName);
            attributeMap.put(this.principalDnAttributeName, CollectionUtils.wrapList(ldapEntry.getDn()));
        }
        return attributeMap;
    }

    /**
     * Gets ldap principal identifier. If the principal id attribute is defined, it's retrieved.
     * If no attribute value is found, a warning is generated and the provided username is used instead.
     * If no attribute is defined, username is used instead.
     *
     * @param username  the username
     * @param ldapEntry the ldap entry
     * @return the ldap principal identifier
     * @throws LoginException in case the principal id cannot be determined.
     */
    protected String getLdapPrincipalIdentifier(final String username, final LdapEntry ldapEntry) throws LoginException {
        if (StringUtils.isNotBlank(this.principalIdAttribute)) {
            val principalAttr = ldapEntry.getAttribute(this.principalIdAttribute);
            if (principalAttr == null || principalAttr.size() == 0) {
                if (this.allowMissingPrincipalAttributeValue) {
                    LOGGER.warn("The principal id attribute [{}] is not found. CAS cannot construct the final authenticated principal "
                            + "if it's unable to locate the attribute that is designated as the principal id. "
                            + "Attributes available on the LDAP entry are [{}]. Since principal id attribute is not available, CAS will "
                            + "fall back to construct the principal based on the provided user id: [{}]",
                        this.principalIdAttribute, ldapEntry.getAttributes(), username);
                    return username;
                }
                LOGGER.error("The principal id attribute [{}] is not found. CAS is configured to disallow missing principal attributes",
                    this.principalIdAttribute);
                throw new LoginException("Principal id attribute is not found for " + principalAttr);
            }
            val value = principalAttr.getStringValue();
            if (principalAttr.size() > 1) {
                if (!this.allowMultiplePrincipalAttributeValues) {
                    throw new LoginException("Multiple principal values are not allowed: " + principalAttr);
                }
                LOGGER.warn("Found multiple values for principal id attribute: [{}]. Using first value=[{}].", principalAttr, value);
            }
            LOGGER.debug("Retrieved principal id attribute [{}]", value);
            return value;
        }
        LOGGER.debug("Principal id attribute is not defined. Using the default provided user id [{}]", username);
        return username;
    }

    /**
     * Initialize the handler, setup the authentication entry attributes.
     */
    public void initialize() {
        /*
         * Use a set to ensure we ignore duplicates.
         */
        val attributes = new HashSet<String>();
        LOGGER.debug("Initializing LDAP attribute configuration...");
        if (StringUtils.isNotBlank(this.principalIdAttribute)) {
            LOGGER.debug("Configured to retrieve principal id attribute [{}]", this.principalIdAttribute);
            attributes.add(this.principalIdAttribute);
        }
        if (this.principalAttributeMap != null && !this.principalAttributeMap.isEmpty()) {
            val attrs = this.principalAttributeMap.keySet();
            attributes.addAll(attrs);
            LOGGER.debug("Configured to retrieve principal attribute collection of [{}]", attrs);
        }
        if (authenticator.getReturnAttributes() != null) {
            val authenticatorAttributes = CollectionUtils.wrapList(authenticator.getReturnAttributes());
            if (!authenticatorAttributes.isEmpty()) {
                LOGGER.debug("Filtering authentication entry attributes [{}] based on authenticator attributes [{}]", authenticatedEntryAttributes, authenticatorAttributes);
                attributes.removeIf(authenticatorAttributes::contains);
            }
        }
        this.authenticatedEntryAttributes = attributes.toArray(ArrayUtils.EMPTY_STRING_ARRAY);
        LOGGER.debug("LDAP authentication entry attributes for the authentication request are [{}]", (Object[]) this.authenticatedEntryAttributes);
    }
}
