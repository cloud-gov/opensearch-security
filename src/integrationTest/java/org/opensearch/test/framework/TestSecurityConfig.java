/*
* Copyright 2021 floragunn GmbH
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/

/*
* SPDX-License-Identifier: Apache-2.0
*
* The OpenSearch Contributors require contributions made to
* this file be licensed under the Apache-2.0 license or a
* compatible open source license.
*
* Modifications Copyright OpenSearch Contributors. See
* GitHub history for details.
*/

package org.opensearch.test.framework;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.security.hasher.PasswordHasher;
import org.opensearch.security.hasher.PasswordHasherFactory;
import org.opensearch.security.securityconf.impl.CType;
import org.opensearch.security.securityconf.impl.SecurityDynamicConfiguration;
import org.opensearch.security.securityconf.impl.v7.ActionGroupsV7;
import org.opensearch.security.securityconf.impl.v7.ConfigV7;
import org.opensearch.security.securityconf.impl.v7.InternalUserV7;
import org.opensearch.security.securityconf.impl.v7.RoleMappingsV7;
import org.opensearch.security.securityconf.impl.v7.RoleV7;
import org.opensearch.security.support.ConfigConstants;
import org.opensearch.test.framework.cluster.OpenSearchClientProvider.UserCredentialsHolder;
import org.opensearch.transport.client.Client;

import static org.apache.http.HttpHeaders.AUTHORIZATION;
import static org.opensearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;

/**
* This class allows the declarative specification of the security configuration; in particular:
*
* - config.yml
* - internal_users.yml
* - roles.yml
* - roles_mapping.yml
*
* The class does the whole round-trip, i.e., the configuration is serialized to YAML/JSON and then written to
* the configuration index of the security plugin.
*/
public class TestSecurityConfig {

    public static final String REST_ADMIN_REST_API_ACCESS = "rest_admin__rest_api_access";

    private static final Logger log = LogManager.getLogger(TestSecurityConfig.class);

    private static final PasswordHasher passwordHasher = PasswordHasherFactory.createPasswordHasher(
        Settings.builder().put(ConfigConstants.SECURITY_PASSWORD_HASHING_ALGORITHM, ConfigConstants.BCRYPT).build()
    );

    private Config config = new Config();
    private Map<String, User> internalUsers = new LinkedHashMap<>();
    private Map<String, Role> roles = new LinkedHashMap<>();
    private AuditConfiguration auditConfiguration;
    private Map<String, RoleMapping> rolesMapping = new LinkedHashMap<>();

    private Map<String, ActionGroup> actionGroups = new LinkedHashMap<>();

    /**
     * A map from document id to a string containing config JSON.
     * If this is not null, it will be used ALTERNATIVELY to all other configuration contained in this class.
     * Can be used to simulate invalid configuration or legacy configuration.
     */
    private Map<String, String> rawConfigurationDocuments;

    private String indexName = ".opendistro_security";

    public TestSecurityConfig() {

    }

    public TestSecurityConfig configIndexName(String configIndexName) {
        this.indexName = configIndexName;
        return this;
    }

    public TestSecurityConfig authFailureListeners(AuthFailureListeners listener) {
        config.authFailureListeners(listener);
        return this;
    }

    public TestSecurityConfig anonymousAuth(boolean anonymousAuthEnabled) {
        config.anonymousAuth(anonymousAuthEnabled);
        return this;
    }

    public TestSecurityConfig doNotFailOnForbidden(boolean doNotFailOnForbidden) {
        config.doNotFailOnForbidden(doNotFailOnForbidden);
        return this;
    }

    public TestSecurityConfig xff(XffConfig xffConfig) {
        config.xffConfig(xffConfig);
        return this;
    }

    public TestSecurityConfig onBehalfOf(OnBehalfOfConfig onBehalfOfConfig) {
        config.onBehalfOfConfig(onBehalfOfConfig);
        return this;
    }

    public TestSecurityConfig authc(AuthcDomain authcDomain) {
        config.authc(authcDomain);
        return this;
    }

    public TestSecurityConfig authz(AuthzDomain authzDomain) {
        config.authz(authzDomain);
        return this;
    }

    public TestSecurityConfig user(User user) {
        this.internalUsers.put(user.name, user);

        for (Role role : user.roles) {
            this.roles.put(role.name, role);
        }

        return this;
    }

    public TestSecurityConfig users(User... users) {
        for (User user : users) {
            this.user(user);
        }
        return this;
    }

    public TestSecurityConfig withRestAdminUser(final String name, final String... permissions) {
        if (!internalUsers.containsKey(name)) {
            user(new User(name).description("REST Admin with permissions: " + Arrays.toString(permissions)).reserved(true));
            final var roleName = name + "__rest_admin_role";
            roles(new Role(roleName).clusterPermissions(permissions));

            rolesMapping.computeIfAbsent(roleName, RoleMapping::new).users(name);
            rolesMapping.computeIfAbsent(REST_ADMIN_REST_API_ACCESS, RoleMapping::new).users(name);
        }
        return this;
    }

    public List<User> getUsers() {
        return new ArrayList<>(internalUsers.values());
    }

    public TestSecurityConfig roles(Role... roles) {
        for (Role role : roles) {
            if (this.roles.containsKey(role.name)) {
                throw new IllegalStateException("Role with name " + role.name + " is already defined");
            }
            this.roles.put(role.name, role);
        }

        return this;
    }

    public List<Role> roles() {
        return List.copyOf(roles.values());
    }

    public TestSecurityConfig audit(AuditConfiguration auditConfiguration) {
        this.auditConfiguration = auditConfiguration;
        return this;
    }

    public TestSecurityConfig rolesMapping(RoleMapping... mappings) {
        for (RoleMapping mapping : mappings) {
            String roleName = mapping.name();
            if (rolesMapping.containsKey(roleName)) {
                throw new IllegalArgumentException("Role mapping " + roleName + " already exists");
            }
            this.rolesMapping.put(roleName, mapping);
        }
        return this;
    }

    public List<RoleMapping> rolesMapping() {
        return List.copyOf(rolesMapping.values());
    }

    public TestSecurityConfig actionGroups(ActionGroup... groups) {
        for (final var group : groups) {
            this.actionGroups.put(group.name, group);
        }
        return this;
    }

    public List<ActionGroup> actionGroups() {
        return List.copyOf(actionGroups.values());
    }

    /**
     * Specifies raw document content for the configuration index as YAML document. If this method is used,
     * then ONLY the raw documents will be written to the configuration index. Any other configuration specified
     * by the roles() or users() method will be ignored.
     * Can be used to simulate invalid configuration or legacy configuration.
     */
    public TestSecurityConfig rawConfigurationDocumentYaml(String configTypeId, String configDocumentAsYaml) {
        try {
            if (this.rawConfigurationDocuments == null) {
                this.rawConfigurationDocuments = new LinkedHashMap<>();
            }

            JsonNode node = new ObjectMapper(new YAMLFactory()).readTree(configDocumentAsYaml);

            this.rawConfigurationDocuments.put(configTypeId, new ObjectMapper().writeValueAsString(node));
            return this;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Config implements ToXContentObject {
        private boolean anonymousAuth;

        private Boolean doNotFailOnForbidden;
        private XffConfig xffConfig;
        private OnBehalfOfConfig onBehalfOfConfig;
        private Map<String, AuthcDomain> authcDomainMap = new LinkedHashMap<>();

        private AuthFailureListeners authFailureListeners;
        private Map<String, AuthzDomain> authzDomainMap = new LinkedHashMap<>();

        public Config anonymousAuth(boolean anonymousAuth) {
            this.anonymousAuth = anonymousAuth;
            return this;
        }

        public Config doNotFailOnForbidden(Boolean doNotFailOnForbidden) {
            this.doNotFailOnForbidden = doNotFailOnForbidden;
            return this;
        }

        public Config xffConfig(XffConfig xffConfig) {
            this.xffConfig = xffConfig;
            return this;
        }

        public Config onBehalfOfConfig(OnBehalfOfConfig onBehalfOfConfig) {
            this.onBehalfOfConfig = onBehalfOfConfig;
            return this;
        }

        public Config authc(AuthcDomain authcDomain) {
            authcDomainMap.put(authcDomain.id, authcDomain);
            return this;
        }

        public Config authFailureListeners(AuthFailureListeners authFailureListeners) {
            this.authFailureListeners = authFailureListeners;
            return this;
        }

        public Config authz(AuthzDomain authzDomain) {
            authzDomainMap.put(authzDomain.getId(), authzDomain);
            return this;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
            xContentBuilder.startObject();
            xContentBuilder.startObject("dynamic");

            if (onBehalfOfConfig != null) {
                xContentBuilder.field("on_behalf_of", onBehalfOfConfig);
            }

            if (anonymousAuth || (xffConfig != null)) {
                xContentBuilder.startObject("http");
                xContentBuilder.field("anonymous_auth_enabled", anonymousAuth);
                if (xffConfig != null) {
                    xContentBuilder.field("xff", xffConfig);
                }
                xContentBuilder.endObject();
            }
            if (doNotFailOnForbidden != null) {
                xContentBuilder.field("do_not_fail_on_forbidden", doNotFailOnForbidden);
            }

            xContentBuilder.field("authc", authcDomainMap);
            if (authzDomainMap.isEmpty() == false) {
                xContentBuilder.field("authz", authzDomainMap);
            }

            if (authFailureListeners != null) {
                xContentBuilder.field("auth_failure_listeners", authFailureListeners);
            }

            xContentBuilder.endObject();
            xContentBuilder.endObject();
            return xContentBuilder;
        }
    }

    public static final class ActionGroup implements ToXContentObject {

        public enum Type {

            INDEX,

            CLUSTER;

            public String type() {
                return name().toLowerCase();
            }

        }

        private final String name;

        private final String description;

        private final Type type;

        private final List<String> allowedActions;

        private Boolean hidden = null;

        private Boolean reserved = null;

        private Boolean _static = null;

        public ActionGroup(String name, Type type, String... allowedActions) {
            this(name, null, type, allowedActions);
        }

        public ActionGroup(String name, String description, Type type, String... allowedActions) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.allowedActions = Arrays.asList(allowedActions);
        }

        public String name() {
            return name;
        }

        public ActionGroup hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        public boolean hidden() {
            return hidden != null && hidden;
        }

        public ActionGroup reserved(boolean reserved) {
            this.reserved = reserved;
            return this;
        }

        public boolean reserved() {
            return reserved != null && reserved;
        }

        public ActionGroup _static(boolean _static) {
            this._static = _static;
            return this;
        }

        public boolean _static() {
            return _static != null && _static;
        }

        public List<String> allowedActions() {
            return allowedActions;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (hidden != null) builder.field("hidden", hidden);
            if (reserved != null) builder.field("reserved", reserved);
            if (_static != null) builder.field("static", _static);
            builder.field("type", type.type());
            builder.field("allowed_actions", allowedActions);
            if (description != null) builder.field("description", description);
            return builder.endObject();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActionGroup that = (ActionGroup) o;
            return Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && type == that.type
                && Objects.equals(allowedActions, that.allowedActions)
                && Objects.equals(hidden, that.hidden)
                && Objects.equals(reserved, that.reserved);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, description, type, allowedActions, hidden, reserved);
        }

    }

    public static final class User implements UserCredentialsHolder, ToXContentObject {

        public final static TestSecurityConfig.User USER_ADMIN = new User("admin").roles(
            new Role("allaccess").indexPermissions("*").on("*").clusterPermissions("*")
        );

        String name;
        private String password;
        List<Role> roles = new ArrayList<>();
        List<String> backendRoles = new ArrayList<>();
        String requestedTenant;
        private Map<String, String> attributes = new HashMap<>();
        private Map<MetadataKey<?>, Object> matchers = new HashMap<>();

        private Boolean hidden = null;

        private Boolean reserved = null;

        private String description;

        private String hash;

        public User(String name) {
            this.name = name;
            this.password = "secret";
        }

        public User description(String description) {
            this.description = description;
            return this;
        }

        public User password(String password) {
            this.password = password;
            return this;
        }

        public User roles(Role... roles) {
            // We scope the role names by user to keep tests free of potential side effects
            String roleNamePrefix = "user_" + this.getName() + "__";
            this.roles.addAll(
                Arrays.asList(roles).stream().map((r) -> r.clone().name(roleNamePrefix + r.getName())).collect(Collectors.toSet())
            );
            return this;
        }

        public User backendRoles(String... backendRoles) {
            this.backendRoles.addAll(Arrays.asList(backendRoles));
            return this;
        }

        public User reserved(boolean reserved) {
            this.reserved = reserved;
            return this;
        }

        public User hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        public User attr(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public User hash(String hash) {
            this.hash = hash;
            return this;
        }

        /**
         * This method can be used to associate arbitrary data with a user, which is later supposed to act as a
         * reference or test oracle inside a test.
         */
        public <T> User reference(MetadataKey<T> key, T data) {
            this.matchers.put(key, data);
            return this;
        }

        public String getName() {
            return name;
        }

        public String getPassword() {
            return password;
        }

        public Set<String> getRoleNames() {
            return roles.stream().map(Role::getName).collect(Collectors.toSet());
        }

        public Object getAttribute(String attributeName) {
            return attributes.get(attributeName);
        }

        public Map<String, String> getAttributes() {
            return this.attributes;
        }

        public <T> T reference(MetadataKey<T> key) {
            Object result = this.matchers.get(key);
            if (result != null) {
                return key.type.cast(result);
            } else {
                return null;
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
            xContentBuilder.startObject();

            if (this.hash == null) {
                xContentBuilder.field("hash", hashPassword(password));
            } else {
                xContentBuilder.field("hash", hash);
            }
            Set<String> roleNames = getRoleNames();

            if (!roleNames.isEmpty()) {
                xContentBuilder.field("opendistro_security_roles", roleNames);
            }

            if (!backendRoles.isEmpty()) {
                xContentBuilder.field("backend_roles", backendRoles);
            }

            if (attributes != null && attributes.size() != 0) {
                xContentBuilder.field("attributes", attributes);
            }

            if (hidden != null) xContentBuilder.field("hidden", hidden);
            if (reserved != null) xContentBuilder.field("reserved", reserved);
            if (!Strings.isNullOrEmpty(description)) xContentBuilder.field("description", description);
            xContentBuilder.endObject();
            return xContentBuilder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            User user = (User) o;
            return Objects.equals(name, user.name)
                && Objects.equals(password, user.password)
                && Objects.equals(roles, user.roles)
                && Objects.equals(backendRoles, user.backendRoles)
                && Objects.equals(requestedTenant, user.requestedTenant)
                && Objects.equals(attributes, user.attributes)
                && Objects.equals(hidden, user.hidden)
                && Objects.equals(reserved, user.reserved)
                && Objects.equals(description, user.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, password, roles, backendRoles, requestedTenant, attributes, hidden, reserved, description);
        }

        @Override
        public String toString() {
            if (description == null) {
                return name;
            } else {
                return name + ": " + description;
            }
        }

        public static class MetadataKey<T> {
            private final String name;
            private final Class<T> type;

            public MetadataKey(String name, Class<T> type) {
                this.name = name;
                this.type = type;
            }
        }
    }

    public static class Role implements ToXContentObject {
        public static Role ALL_ACCESS = new Role("all_access").clusterPermissions("*").indexPermissions("*").on("*");

        private String name;
        private List<String> clusterPermissions = new ArrayList<>();

        private List<IndexPermission> indexPermissions = new ArrayList<>();

        private Boolean hidden;

        private Boolean reserved;

        private String description;

        public Role(String name) {
            this(name, null);
        }

        public Role(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Role clusterPermissions(String... clusterPermissions) {
            this.clusterPermissions.addAll(Arrays.asList(clusterPermissions));
            return this;
        }

        public IndexPermission indexPermissions(String... indexPermissions) {
            return new IndexPermission(this, indexPermissions);
        }

        public Role name(String name) {
            this.name = name;
            return this;
        }

        public String getName() {
            return name;
        }

        public Role hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        public Role reserved(boolean reserved) {
            this.reserved = reserved;
            return this;
        }

        public Role clone() {
            Role role = new Role(this.name);
            role.clusterPermissions.addAll(this.clusterPermissions);
            role.indexPermissions.addAll(this.indexPermissions);
            return role;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
            xContentBuilder.startObject();

            if (!clusterPermissions.isEmpty()) {
                xContentBuilder.field("cluster_permissions", clusterPermissions);
            }

            if (!indexPermissions.isEmpty()) {
                xContentBuilder.field("index_permissions", indexPermissions);
            }
            if (hidden != null) {
                xContentBuilder.field("hidden", hidden);
            }
            if (reserved != null) {
                xContentBuilder.field("reserved", reserved);
            }
            if (!Strings.isNullOrEmpty(description)) xContentBuilder.field("description", description);
            return xContentBuilder.endObject();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Role role = (Role) o;
            return Objects.equals(name, role.name)
                && Objects.equals(clusterPermissions, role.clusterPermissions)
                && Objects.equals(indexPermissions, role.indexPermissions)
                && Objects.equals(hidden, role.hidden)
                && Objects.equals(reserved, role.reserved)
                && Objects.equals(description, role.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, clusterPermissions, indexPermissions, hidden, reserved, description);
        }

        public static SecurityDynamicConfiguration<org.opensearch.security.securityconf.impl.v7.RoleV7> toRolesConfiguration(
            TestSecurityConfig.Role... roles
        ) {
            try {
                return SecurityDynamicConfiguration.fromJson(
                    configToJson(CType.ROLES, Stream.of(roles).collect(Collectors.toMap(r -> r.name, r -> r))),
                    CType.ROLES,
                    2,
                    0,
                    0
                );
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class RoleMapping implements ToXContentObject {

        private List<String> users = new ArrayList<>();
        private List<String> hosts = new ArrayList<>();

        private final String name;

        private Boolean hidden;

        private Boolean reserved;

        private Boolean _static;

        private final String description;

        private List<String> backendRoles = new ArrayList<>();

        public RoleMapping(final String name) {
            this(name, null);
        }

        public RoleMapping(final String name, final String description) {
            this.name = name;
            this.description = description;
        }

        public String name() {
            return name;
        }

        public RoleMapping hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        public RoleMapping reserved(boolean reserved) {
            this.reserved = reserved;
            return this;
        }

        public RoleMapping _static(boolean _static) {
            this._static = _static;
            return this;
        }

        public RoleMapping users(String... users) {
            this.users.addAll(Arrays.asList(users));
            return this;
        }

        public RoleMapping hosts(String... hosts) {
            this.users.addAll(Arrays.asList(hosts));
            return this;
        }

        public RoleMapping backendRoles(String... backendRoles) {
            this.backendRoles.addAll(Arrays.asList(backendRoles));
            return this;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (hidden != null) builder.field("hidden", hidden);
            if (reserved != null) builder.field("reserved", reserved);
            if (_static != null) builder.field("static", _static);
            if (users != null && !users.isEmpty()) builder.field("users", users);
            if (hosts != null && !hosts.isEmpty()) builder.field("hosts", hosts);
            if (description != null) builder.field("description", description);
            builder.field("backend_roles", backendRoles);
            return builder.endObject();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RoleMapping that = (RoleMapping) o;
            return Objects.equals(users, that.users)
                && Objects.equals(hosts, that.hosts)
                && Objects.equals(name, that.name)
                && Objects.equals(hidden, that.hidden)
                && Objects.equals(reserved, that.reserved)
                && Objects.equals(description, that.description)
                && Objects.equals(backendRoles, that.backendRoles);
        }

        @Override
        public int hashCode() {
            return Objects.hash(users, hosts, name, hidden, reserved, description, backendRoles);
        }
    }

    public static class IndexPermission implements ToXContentObject {
        private List<String> allowedActions;
        private List<String> indexPatterns;
        private Role role;
        private String dlsQuery;
        private List<String> fls;
        private List<String> maskedFields;

        IndexPermission(Role role, String... allowedActions) {
            this.allowedActions = Arrays.asList(allowedActions);
            this.role = role;
        }

        public IndexPermission dls(String dlsQuery) {
            this.dlsQuery = dlsQuery;
            return this;
        }

        public IndexPermission dls(QueryBuilder dlsQuery) {
            this.dlsQuery = Strings.toString(MediaTypeRegistry.JSON, dlsQuery);
            return this;
        }

        public IndexPermission fls(String... fls) {
            this.fls = Arrays.asList(fls);
            return this;
        }

        public IndexPermission maskedFields(String... maskedFields) {
            this.maskedFields = Arrays.asList(maskedFields);
            return this;
        }

        public Role on(String... indexPatterns) {
            this.indexPatterns = Arrays.asList(indexPatterns);
            this.role.indexPermissions.add(this);
            return this.role;
        }

        public Role on(TestIndex... testindices) {
            this.indexPatterns = Arrays.asList(testindices).stream().map(TestIndex::name).collect(Collectors.toList());
            this.role.indexPermissions.add(this);
            return this.role;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
            xContentBuilder.startObject();

            xContentBuilder.field("index_patterns", indexPatterns);
            xContentBuilder.field("allowed_actions", allowedActions);

            if (dlsQuery != null) {
                xContentBuilder.field("dls", dlsQuery);
            }

            if (fls != null) {
                xContentBuilder.field("fls", fls);
            }

            if (maskedFields != null) {
                xContentBuilder.field("masked_fields", maskedFields);
            }

            xContentBuilder.endObject();
            return xContentBuilder;
        }
    }

    public static class AuthcDomain implements ToXContentObject {

        private static String PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoqZbjLUAWc+DZTkinQAdvy1GFjPHPnxheU89hSiWoDD3NOW76H3u3T7cCDdOah2msdxSlBmCBH6wik8qLYkcV8owWukQg3PQmbEhrdPaKo0QCgomWs4nLgtmEYqcZ+QQldd82MdTlQ1QmoQmI9Uxqs1SuaKZASp3Gy19y8su5CV+FZ6BruUw9HELK055sAwl3X7j5ouabXGbcib2goBF3P52LkvbJLuWr5HDZEOeSkwIeqSeMojASM96K5SdotD+HwEyjaTjzRPL2Aa1BEQFWOQ6CFJLyLH7ZStDuPM1mJU1VxIVfMbZrhsUBjAnIhRynmWxML7YlNqkP9j6jyOIYQIDAQAB";

        public static final int BASIC_AUTH_DOMAIN_ORDER = 0;
        public final static AuthcDomain AUTHC_HTTPBASIC_INTERNAL = new TestSecurityConfig.AuthcDomain("basic", BASIC_AUTH_DOMAIN_ORDER)
            .httpAuthenticatorWithChallenge("basic")
            .backend("internal");

        public final static AuthcDomain AUTHC_HTTPBASIC_INTERNAL_WITHOUT_CHALLENGE = new TestSecurityConfig.AuthcDomain(
            "basic",
            BASIC_AUTH_DOMAIN_ORDER
        ).httpAuthenticator("basic").backend("internal");

        public final static AuthcDomain DISABLED_AUTHC_HTTPBASIC_INTERNAL = new TestSecurityConfig.AuthcDomain(
            "basic",
            BASIC_AUTH_DOMAIN_ORDER,
            false
        ).httpAuthenticator("basic").backend("internal");

        public final static AuthcDomain JWT_AUTH_DOMAIN = new TestSecurityConfig.AuthcDomain("jwt", 1).jwtHttpAuthenticator(
            new JwtConfigBuilder().jwtHeader(AUTHORIZATION).signingKey(List.of(PUBLIC_KEY))
        ).backend("noop");

        private final String id;
        private boolean enabled = true;
        private int order;
        private List<String> skipUsers = new ArrayList<>();
        private HttpAuthenticator httpAuthenticator;
        private AuthenticationBackend authenticationBackend;

        public AuthcDomain(String id, int order, boolean enabled) {
            this.id = id;
            this.order = order;
            this.enabled = enabled;
        }

        public AuthcDomain(String id, int order) {
            this(id, order, true);
        }

        public AuthcDomain httpAuthenticator(String type) {
            this.httpAuthenticator = new HttpAuthenticator(type);
            return this;
        }

        public AuthcDomain jwtHttpAuthenticator(JwtConfigBuilder builder) {
            this.httpAuthenticator = new HttpAuthenticator("jwt").challenge(false).config(builder.build());
            return this;
        }

        public AuthcDomain httpAuthenticatorWithChallenge(String type) {
            this.httpAuthenticator = new HttpAuthenticator(type).challenge(true);
            return this;
        }

        public AuthcDomain httpAuthenticator(HttpAuthenticator httpAuthenticator) {
            this.httpAuthenticator = httpAuthenticator;
            return this;
        }

        public AuthcDomain backend(String type) {
            this.authenticationBackend = new AuthenticationBackend(type);
            return this;
        }

        public AuthcDomain backend(AuthenticationBackend authenticationBackend) {
            this.authenticationBackend = authenticationBackend;
            return this;
        }

        public AuthcDomain skipUsers(String... users) {
            this.skipUsers.addAll(Arrays.asList(users));
            return this;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
            xContentBuilder.startObject();

            xContentBuilder.field("http_enabled", enabled);
            xContentBuilder.field("order", order);

            if (httpAuthenticator != null) {
                xContentBuilder.field("http_authenticator", httpAuthenticator);
            }

            if (authenticationBackend != null) {
                xContentBuilder.field("authentication_backend", authenticationBackend);
            }

            if (skipUsers != null && skipUsers.size() > 0) {
                xContentBuilder.field("skip_users", skipUsers);
            }

            xContentBuilder.endObject();
            return xContentBuilder;
        }

        public static class HttpAuthenticator implements ToXContentObject {
            private final String type;
            private boolean challenge;
            private Map<String, Object> config = new HashMap();

            public HttpAuthenticator(String type) {
                this.type = type;
            }

            public HttpAuthenticator challenge(boolean challenge) {
                this.challenge = challenge;
                return this;
            }

            public HttpAuthenticator config(Map<String, Object> config) {
                this.config.putAll(config);
                return this;
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
                xContentBuilder.startObject();

                xContentBuilder.field("type", type);
                xContentBuilder.field("challenge", challenge);
                xContentBuilder.field("config", config);

                xContentBuilder.endObject();
                return xContentBuilder;
            }
        }

        public static class AuthenticationBackend implements ToXContentObject {
            private final String type;
            private Supplier<Map<String, Object>> config = () -> new HashMap();

            public AuthenticationBackend(String type) {
                this.type = type;
            }

            public AuthenticationBackend config(Map<String, Object> config) {
                Map<String, Object> configCopy = new HashMap<>(config);
                this.config = () -> configCopy;
                return this;
            }

            public AuthenticationBackend config(Supplier<Map<String, Object>> configSupplier) {
                this.config = configSupplier;
                return this;
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder xContentBuilder, Params params) throws IOException {
                xContentBuilder.startObject();

                xContentBuilder.field("type", type);
                xContentBuilder.field("config", config.get());

                xContentBuilder.endObject();
                return xContentBuilder;
            }
        }
    }

    public void initIndex(Client client) {
        Map<String, Object> settings = new HashMap<>();
        if (indexName.startsWith(".")) {
            settings.put("index.hidden", true);
        }
        client.admin().indices().create(new CreateIndexRequest(indexName).settings(settings)).actionGet();

        if (rawConfigurationDocuments == null) {
            writeSingleEntryConfigToIndex(client, CType.CONFIG, config);
            if (auditConfiguration != null) {
                writeSingleEntryConfigToIndex(client, CType.AUDIT, "config", auditConfiguration);
            }
            writeConfigToIndex(client, CType.ROLES, roles);
            writeConfigToIndex(client, CType.INTERNALUSERS, internalUsers);
            writeConfigToIndex(client, CType.ROLESMAPPING, rolesMapping);
            writeEmptyConfigToIndex(client, CType.ACTIONGROUPS);
            writeEmptyConfigToIndex(client, CType.TENANTS);
        } else {
            // Write raw configuration alternatively to the normal configuration

            for (Map.Entry<String, String> entry : this.rawConfigurationDocuments.entrySet()) {
                writeConfigToIndex(client, entry.getKey(), entry.getValue());
            }
        }

    }

    public void updateInternalUsersConfiguration(Client client, List<User> users) {
        Map<String, ToXContentObject> userMap = new HashMap<>();
        for (User user : users) {
            userMap.put(user.getName(), user);
        }
        updateConfigInIndex(client, CType.INTERNALUSERS, userMap);
    }

    public SecurityDynamicConfiguration<ConfigV7> getSecurityConfiguration() {
        try {
            return SecurityDynamicConfiguration.fromJson(
                singleEntryConfigToJson(CType.CONFIG, CType.CONFIG.toLCString(), config),
                CType.CONFIG,
                2,
                0,
                0
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SecurityDynamicConfiguration<InternalUserV7> getInternalUserConfiguration() {
        try {
            return SecurityDynamicConfiguration.fromJson(configToJson(CType.INTERNALUSERS, internalUsers), CType.INTERNALUSERS, 2, 0, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SecurityDynamicConfiguration<RoleV7> getRolesConfiguration() {
        try {
            return SecurityDynamicConfiguration.fromJson(configToJson(CType.ROLES, roles), CType.ROLES, 2, 0, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SecurityDynamicConfiguration<RoleMappingsV7> getRoleMappingsConfiguration() {
        try {
            return SecurityDynamicConfiguration.fromJson(configToJson(CType.ROLESMAPPING, rolesMapping), CType.ROLESMAPPING, 2, 0, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SecurityDynamicConfiguration<ActionGroupsV7> geActionGroupsConfiguration() {
        try {
            return SecurityDynamicConfiguration.fromJson(configToJson(CType.ACTIONGROUPS, actionGroups), CType.ACTIONGROUPS, 2, 0, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static String hashPassword(final String clearTextPassword) {
        return passwordHasher.hash(clearTextPassword.toCharArray());
    }

    private void writeEmptyConfigToIndex(Client client, CType<?> configType) {
        writeConfigToIndex(client, configType, Collections.emptyMap());
    }

    private void writeConfigToIndex(Client client, CType<?> configType, Map<String, ? extends ToXContentObject> config) {
        try {
            String json = configToJson(configType, config);

            log.info("Writing security configuration into index " + configType + ":\n" + json);

            BytesReference bytesReference = toByteReference(json);
            client.index(
                new IndexRequest(indexName).id(configType.toLCString())
                    .setRefreshPolicy(IMMEDIATE)
                    .source(configType.toLCString(), bytesReference)
            ).actionGet();
        } catch (Exception e) {
            throw new RuntimeException("Error while initializing config for " + indexName, e);
        }
    }

    private void writeConfigToIndex(Client client, String documentId, String jsonString) {
        try {
            log.info("Writing raw security configuration into index {}:\n{}", documentId, jsonString);

            BytesReference bytesReference = toByteReference(jsonString);
            client.index(new IndexRequest(indexName).id(documentId).setRefreshPolicy(IMMEDIATE).source(documentId, bytesReference))
                .actionGet();
        } catch (Exception e) {
            throw new RuntimeException("Error while initializing config for " + indexName, e);
        }
    }

    private static BytesReference toByteReference(String string) throws UnsupportedEncodingException {
        return BytesReference.fromByteBuffer(ByteBuffer.wrap(string.getBytes("utf-8")));
    }

    private void updateConfigInIndex(Client client, CType<?> configType, Map<String, ? extends ToXContentObject> config) {
        try {
            String json = configToJson(configType, config);
            BytesReference bytesReference = toByteReference(json);
            log.info("Update configuration of type '{}' in index '{}', new value '{}'.", configType, indexName, json);
            UpdateRequest upsert = new UpdateRequest(indexName, configType.toLCString()).doc(configType.toLCString(), bytesReference)
                .setRefreshPolicy(IMMEDIATE);
            client.update(upsert).actionGet();
        } catch (Exception e) {
            throw new RuntimeException("Error while updating config for " + indexName, e);
        }
    }

    private static String configToJson(CType<?> configType, Map<String, ? extends ToXContentObject> config) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();

        builder.startObject();
        builder.startObject("_meta");
        builder.field("type", configType.toLCString());
        builder.field("config_version", 2);
        builder.endObject();

        for (Map.Entry<String, ? extends ToXContentObject> entry : config.entrySet()) {
            builder.field(entry.getKey(), entry.getValue());
        }

        builder.endObject();

        return builder.toString();
    }

    private static String singleEntryConfigToJson(CType<?> configType, String configurationRoot, ToXContentObject config)
        throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();

        builder.startObject();
        builder.startObject("_meta");
        builder.field("type", configType.toLCString());
        builder.field("config_version", 2);
        builder.endObject();

        builder.field(configurationRoot, config);

        builder.endObject();

        return builder.toString();
    }

    private void writeSingleEntryConfigToIndex(Client client, CType<?> configType, ToXContentObject config) {
        writeSingleEntryConfigToIndex(client, configType, configType.toLCString(), config);
    }

    private void writeSingleEntryConfigToIndex(Client client, CType<?> configType, String configurationRoot, ToXContentObject config) {
        try {
            String json = singleEntryConfigToJson(configType, configurationRoot, config);

            log.info("Writing security plugin configuration into index " + configType + ":\n" + json);

            client.index(
                new IndexRequest(indexName).id(configType.toLCString())
                    .setRefreshPolicy(IMMEDIATE)
                    .source(configType.toLCString(), toByteReference(json))
            ).actionGet();
        } catch (Exception e) {
            throw new RuntimeException("Error while initializing config for " + indexName, e);
        }
    }
}
