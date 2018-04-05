/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 * Copyright (c) 2017-2018, bguerin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.bitbucket.client;

import com.cloudbees.jenkins.plugins.bitbucket.JsonParser;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketApi;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBuildStatus;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketCommit;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketException;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPullRequest;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepository;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepositoryProtocol;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepositoryType;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRequestException;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketTeam;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketWebHook;
import com.cloudbees.jenkins.plugins.bitbucket.client.branch.BitbucketCloudBranch;
import com.cloudbees.jenkins.plugins.bitbucket.client.branch.BitbucketCloudCommit;
import com.cloudbees.jenkins.plugins.bitbucket.client.pullrequest.BitbucketPullRequestCommit;
import com.cloudbees.jenkins.plugins.bitbucket.client.pullrequest.BitbucketPullRequestCommits;
import com.cloudbees.jenkins.plugins.bitbucket.client.pullrequest.BitbucketPullRequestValue;
import com.cloudbees.jenkins.plugins.bitbucket.client.pullrequest.BitbucketPullRequests;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.BitbucketCloudRepository;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.BitbucketCloudTeam;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.BitbucketRepositoryHook;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.BitbucketRepositoryHooks;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.BitbucketRepositorySource;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.PaginatedBitbucketRepository;
import com.cloudbees.jenkins.plugins.bitbucket.client.repository.UserRoleInRepository;
import com.cloudbees.jenkins.plugins.bitbucket.filesystem.BitbucketSCMFile;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.damnhandy.uri.template.UriTemplate;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.util.Secret;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMFile;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.type.TypeReference;

public class BitbucketCloudApiClient implements BitbucketApi {
    private static final Logger LOGGER = Logger.getLogger(BitbucketCloudApiClient.class.getName());
    private static final HttpHost API_HOST = HttpHost.create("https://api.bitbucket.org");
    private static final String V2_API_BASE_URL = "https://api.bitbucket.org/2.0/repositories";
    private static final String V2_TEAMS_API_BASE_URL = "https://api.bitbucket.org/2.0/teams";
    private static final String REPO_URL_TEMPLATE = V2_API_BASE_URL + "{/owner,repo}";
    private static final int API_RATE_LIMIT_CODE = 429;
    private static final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    private CloseableHttpClient client;
    private HttpClientContext context;
    private final String owner;
    private final String repositoryName;
    private final UsernamePasswordCredentials credentials;
    static {
        connectionManager.setDefaultMaxPerRoute(20);
        connectionManager.setMaxTotal(22);
        connectionManager.setSocketConfig(API_HOST, SocketConfig.custom().setSoTimeout(60 * 1000).build());
    }
    private static Cache<String, BitbucketTeam> cachedTeam = new Cache(6, TimeUnit.HOURS);
    private static Cache<String, List<BitbucketCloudRepository>> cachedRepositories = new Cache(3, TimeUnit.HOURS);
    private transient BitbucketRepository cachedRepository;
    private transient String cachedDefaultBranch;

    public BitbucketCloudApiClient(String owner, String repositoryName, StandardUsernamePasswordCredentials creds) {
        if (creds != null) {
            this.credentials = new UsernamePasswordCredentials(creds.getUsername(), Secret.toString(creds.getPassword()));
        } else {
            this.credentials = null;
        }
        this.owner = owner;
        this.repositoryName = repositoryName;

        // Create Http client
        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();
        httpClientBuilder.setConnectionManager(connectionManager);
        httpClientBuilder.setConnectionManagerShared(true);

        if (credentials != null) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, credentials);
            AuthCache authCache = new BasicAuthCache();
            authCache.put(API_HOST, new BasicScheme());
            context = HttpClientContext.create();
            context.setCredentialsProvider(credentialsProvider);
            context.setAuthCache(authCache);
        }

        setClientProxyParams("bitbucket.org", httpClientBuilder);

        this.client = httpClientBuilder.build();
    }

    @Override
    protected void finalize() throws Throwable {
        if (client != null) {
            client.close();
        }

        super.finalize();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getOwner() {
        return owner;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @CheckForNull
    public String getRepositoryName() {
        return repositoryName;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getRepositoryUri(@NonNull BitbucketRepositoryType type,
                                   @NonNull BitbucketRepositoryProtocol protocol,
                                   @CheckForNull Integer protocolPortOverride,
                                   @NonNull String owner,
                                   @NonNull String repository) {
        // ignore port override on Cloud
        switch (type) {
            case GIT:
                switch (protocol) {
                    case HTTP:
                        return "https://bitbucket.org/" + owner + "/" + repository + ".git";
                    case SSH:
                        return "git@bitbucket.org:" + owner + "/" + repository + ".git";
                    default:
                        throw new IllegalArgumentException("Unsupported repository protocol: " + protocol);
                }
            case MERCURIAL:
                switch (protocol) {
                    case HTTP:
                        return "https://bitbucket.org/" + owner + "/" + repository;
                    case SSH:
                        return "ssh://hg@bitbucket.org/" + owner + "/" + repository;
                    default:
                        throw new IllegalArgumentException("Unsupported repository protocol: " + protocol);
                }
            default:
                throw new IllegalArgumentException("Unsupported repository type: " + type);
        }
    }

    @CheckForNull
    public String getLogin() {
        if (credentials != null) {
            return credentials.getUserName();
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<BitbucketPullRequestValue> getPullRequests() throws InterruptedException, IOException {
        List<BitbucketPullRequestValue> pullRequests = new ArrayList<BitbucketPullRequestValue>();
        int pageNumber = 1;
        UriTemplate template = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/pullrequests{?page,pagelen}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("page", pageNumber)
                .set("pagelen", 100);
        String url = template.expand();

        String response = getRequest(url);
        BitbucketPullRequests page;
        try {
            page = JsonParser.toJava(response, BitbucketPullRequests.class);
        } catch (IOException e) {
            throw new IOException("I/O error when parsing response from URL: " + url, e);
        }
        pullRequests.addAll(page.getValues());
        while (page.getNext() != null) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            pageNumber++;
            response = getRequest(url = template.set("page", pageNumber).expand());
            try {
                page = JsonParser.toJava(response, BitbucketPullRequests.class);
            } catch (IOException e) {
                throw new IOException("I/O error when parsing response from URL: " + url, e);
            }
            pullRequests.addAll(page.getValues());
        }
        return pullRequests;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public BitbucketPullRequest getPullRequestById(@NonNull Integer id) throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/pullrequests{/id}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("id", id)
                .expand();
        String response = getRequest(url);
        try {
            return JsonParser.toJava(response, BitbucketPullRequestValue.class);
        } catch (IOException e) {
            throw new IOException("I/O error when parsing response from URL: " + url, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public BitbucketRepository getRepository() throws IOException, InterruptedException {
        if (repositoryName == null) {
            throw new UnsupportedOperationException("Cannot get a repository from an API instance that is not associated with a repository");
        }
        if (cachedRepository == null) {
            String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE)
                    .set("owner", owner)
                    .set("repo", repositoryName)
                    .expand();
            String response = getRequest(url);
            try {
                cachedRepository =  JsonParser.toJava(response, BitbucketCloudRepository.class);
            } catch (IOException e) {
                throw new IOException("I/O error when parsing response from URL: " + url, e);
            }
        }
        return cachedRepository;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void postCommitComment(@NonNull String hash, @NonNull String comment) throws IOException, InterruptedException {
        String path = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/commit{/hash}/build")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("hash", hash)
                .expand();
        try {
            postRequest(path, Collections.singletonList(new BasicNameValuePair("content", comment)));
        } catch (UnsupportedEncodingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Cannot comment on commit, url: " + path, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkPathExists(@NonNull String branchOrHash, @NonNull String path)
            throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/src{/branchOrHash,path}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("branchOrHash", branchOrHash)
                .set("path", path)
                .expand();
        int status = headRequestStatus(url);
        return status == HttpStatus.SC_OK;
    }

    /**
     * {@inheritDoc}
     */
    @CheckForNull
    @Override
    public String getDefaultBranch() throws IOException, InterruptedException {
        if (cachedDefaultBranch == null) {
            String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/{?fields}")
                    .set("owner", owner)
                    .set("repo", repositoryName)
                    .set("fields", "mainbranch.name")
                    .expand();
            String response;
            try {
                response = getRequest(url);
            } catch (FileNotFoundException e) {
                LOGGER.log(Level.FINE, "Could not find default branch for {0}/{1}",
                        new Object[]{this.owner, this.repositoryName});
                return null;
            }
            Map resp = JsonParser.toJava(response, Map.class);
            Map mainbranch = (Map) resp.get("mainbranch");
            if (mainbranch != null) {
                cachedDefaultBranch = (String) mainbranch.get("name");
            }
        }
        return cachedDefaultBranch;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<BitbucketCloudBranch> getBranches() throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/refs/branches{?pagelen,q}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("pagelen", 100)
                .set("q","(name=\"master\" OR name=\"trunk\" OR name=\"staging\" OR name=\"kubernetes-helm\" OR name=\"live\" OR name=\"dev\" OR name=\"dev-lukas\")")
                .expand();
        String response = getRequest(url);
        try {
            return getAllBranches(response);
        } catch (IOException e) {
            throw new IOException("I/O error when parsing response from URL: " + url, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @CheckForNull
    public BitbucketCommit resolveCommit(@NonNull String hash) throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/commit/{hash}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("hash", hash)
                .expand();
        String response;
        try {
            response = getRequest(url);
        } catch (FileNotFoundException e) {
            return null;
        }
        try {
            return JsonParser.toJava(response, BitbucketCloudCommit.class);
        } catch (IOException e) {
            throw new IOException("I/O error when parsing response from URL: " + url, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String resolveSourceFullHash(@NonNull BitbucketPullRequest pull) throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/pullrequests/{pullId}/commits{?fields,pagelen}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("pullId", pull.getId())
                .set("fields", "values.hash")
                .set("pagelen", 1)
                .expand();
        String response = getRequest(url);
        try {
            BitbucketPullRequestCommits commits = JsonParser.toJava(response, BitbucketPullRequestCommits.class);
            for (BitbucketPullRequestCommit commit : Util.fixNull(commits.getValues())) {
                return commit.getHash();
            }
            throw new BitbucketException("Could not determine commit for pull request " + pull.getId());
        } catch (IOException e) {
            throw new IOException("I/O error when parsing response from URL: " + url, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerCommitWebHook(@NonNull BitbucketWebHook hook) throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/hooks")
                .set("owner", owner)
                .set("repo", repositoryName)
                .expand();
        postRequest(url, JsonParser.toJson(hook));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateCommitWebHook(@NonNull BitbucketWebHook hook) throws IOException, InterruptedException {
        String url = UriTemplate
                .fromTemplate(REPO_URL_TEMPLATE + "/hooks/{hook}")
                .set("hook", hook.getUuid())
                .expand();
        putRequest(url, JsonParser.toJson(hook));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeCommitWebHook(@NonNull BitbucketWebHook hook) throws IOException, InterruptedException {
        if (StringUtils.isBlank(hook.getUuid())) {
            throw new BitbucketException("Hook UUID required");
        }
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/hooks/{uuid}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("uuid", hook.getUuid())
                .expand();
        deleteRequest(url);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<BitbucketRepositoryHook> getWebHooks() throws IOException, InterruptedException {
        List<BitbucketRepositoryHook> repositoryHooks = new ArrayList<BitbucketRepositoryHook>();
        int pageNumber = 1;
        UriTemplate template = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/hooks{?page,pagelen}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("page", pageNumber)
                .set("pagelen", 100);
        String url = template.expand();
        try {
            String response = getRequest(url);
            BitbucketRepositoryHooks page = parsePaginatedRepositoryHooks(response);
            repositoryHooks.addAll(page.getValues());
            while (page.getNext() != null) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                pageNumber++;
                response = getRequest(url = template.set("page", pageNumber).expand());
                page = parsePaginatedRepositoryHooks(response);
                repositoryHooks.addAll(page.getValues());
            }
            return repositoryHooks;
        } catch (IOException e) {
            throw new IOException("I/O error when parsing response from URL: " + url, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void postBuildStatus(@NonNull BitbucketBuildStatus status) throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/commit/{hash}/statuses/build")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("hash", status.getHash())
                .expand();
        postRequest(url, JsonParser.toJson(status));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPrivate() throws IOException, InterruptedException {
        return getRepository().isPrivate();
    }

    private BitbucketRepositoryHooks parsePaginatedRepositoryHooks(String response) throws IOException {
        BitbucketRepositoryHooks parsedResponse;
        parsedResponse = JsonParser.toJava(response, BitbucketRepositoryHooks.class);
        return parsedResponse;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @CheckForNull
    public BitbucketTeam getTeam() throws IOException, InterruptedException {
        final String url = UriTemplate.fromTemplate(V2_TEAMS_API_BASE_URL + "{/owner},{?pagelen}")
                .set("owner", owner)
                .set("pagelen", 100)
                .expand();
        try {
            return cachedTeam.get(owner, new Callable<BitbucketTeam>() {
                @Override
                public BitbucketTeam call() throws Exception {
                    try {
                        String response = getRequest(url);
                        return JsonParser.toJava(response, BitbucketCloudTeam.class);
                    } catch (FileNotFoundException e) {
                        return null;
                    } catch (IOException e) {
                        throw new IOException("I/O error when parsing response from URL: " + url, e);
                    }
                }
            });
        } catch (ExecutionException ex) {
            return null;
        }
    }

    /**
     * The role parameter only makes sense when the request is authenticated, so
     * if there is no auth information ({@link #credentials}) the role will be omitted.
     */
    @NonNull
    @Override
    public List<BitbucketCloudRepository> getRepositories(@CheckForNull UserRoleInRepository role)
            throws InterruptedException, IOException {
        StringBuilder cacheKey = new StringBuilder();
        cacheKey.append(owner).append("::").append(credentials.getUserName());
        final UriTemplate template = UriTemplate.fromTemplate(V2_API_BASE_URL + "{/owner}{?role,page,pagelen}")
                .set("owner", owner)
                .set("pagelen", 100);
        if (role != null && getLogin() != null) {
            template.set("role", role.getId());
            cacheKey.append("::").append(role.getId());
        }
        try {
            return cachedRepositories.get(cacheKey.toString(), new Callable<List<BitbucketCloudRepository>>() {
                @Override
                public List<BitbucketCloudRepository> call() throws Exception {
                    List<BitbucketCloudRepository> repositories = new ArrayList<BitbucketCloudRepository>();
                    Integer pageNumber = 1;
                    String url, response;
                    PaginatedBitbucketRepository page;
                    do {
                        response = getRequest(url = template.set("page", pageNumber).expand());
                        try {
                            page = JsonParser.toJava(response, PaginatedBitbucketRepository.class);
                            repositories.addAll(page.getValues());
                        } catch (IOException e) {
                            throw new IOException("I/O error when parsing response from URL: " + url, e);
                        }
                        pageNumber++;
                    } while (page.getNext() != null);
                    Collections.sort(repositories, new Comparator<BitbucketCloudRepository>() {
                        @Override
                        public int compare(BitbucketCloudRepository o1, BitbucketCloudRepository o2) {
                            return o1.getRepositoryName().compareTo(o2.getRepositoryName());
                        }
                    });
                    return repositories;
                }
            });
        } catch (ExecutionException ex) {
            throw new IOException("Error while loading repositories from cache", ex);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public List<BitbucketCloudRepository> getRepositories() throws IOException, InterruptedException {
        return getRepositories(null);
    }

    private void setClientProxyParams(String host, HttpClientBuilder builder) {
        Jenkins jenkins = Jenkins.getInstance();
        ProxyConfiguration proxyConfig = null;
        if (jenkins != null) {
            proxyConfig = jenkins.proxy;
        }

        Proxy proxy = Proxy.NO_PROXY;
        if (proxyConfig != null) {
            proxy = proxyConfig.createProxy(host);
        }

        if (proxy.type() != Proxy.Type.DIRECT) {
            final InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
            LOGGER.fine("Jenkins proxy: " + proxy.address());
            builder.setProxy(new HttpHost(proxyAddress.getHostName(), proxyAddress.getPort()));
            String username = proxyConfig.getUserName();
            String password = proxyConfig.getPassword();
            if (username != null && !"".equals(username.trim())) {
                LOGGER.fine("Using proxy authentication (user=" + username + ")");
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
                AuthCache authCache = new BasicAuthCache();
                authCache.put(HttpHost.create(proxyAddress.getHostName()), new BasicScheme());
                context = HttpClientContext.create();
                context.setCredentialsProvider(credentialsProvider);
                context.setAuthCache(authCache);
            }
        }
    }

    private CloseableHttpResponse executeMethod(HttpRequestBase httpMethod) throws InterruptedException, IOException {
        RequestConfig.Builder requestConfig = RequestConfig.custom();
        requestConfig.setConnectTimeout(10 * 1000);
        requestConfig.setConnectionRequestTimeout(60 * 1000);
        requestConfig.setSocketTimeout(60 * 1000);
        httpMethod.setConfig(requestConfig.build());

        CloseableHttpResponse response = client.execute(API_HOST, httpMethod, context);
        while (response.getStatusLine().getStatusCode() == API_RATE_LIMIT_CODE) {
            release(httpMethod);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            /*
                TODO: When bitbucket starts supporting rate limit expiration time, remove 5 sec wait and put code
                      to wait till expiration time is over. It should also fix the wait for ever loop.
             */
            LOGGER.fine("Bitbucket Cloud API rate limit reached, sleeping for 5 sec then retry...");
            Thread.sleep(5000);
            response = client.execute(API_HOST, httpMethod, context);
        }
        return response;
    }

    /**
     * Caller's responsible to close the InputStream.
     */
    private InputStream getRequestAsInputStream(String path) throws IOException, InterruptedException {
        HttpGet httpget = new HttpGet(path);
        try {
            CloseableHttpResponse response =  executeMethod(httpget);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                EntityUtils.consume(response.getEntity());
                response.close();
                throw new FileNotFoundException("URL: " + path);
            }
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                String content = IOUtils.toString(response.getEntity().getContent());
                int statusCode = response.getStatusLine().getStatusCode();
                String status = response.getStatusLine().getReasonPhrase();
                EntityUtils.consume(response.getEntity());
                response.close();
                throw new BitbucketRequestException(statusCode,
                        "HTTP request error. Status: " + statusCode + ": " + status + ".\n" + content);
            }
            return new ClosingConnectionInputStream(response, httpget, connectionManager);
        } catch (BitbucketRequestException | FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Communication error for url: " + path, e);
        }
    }

    private String getRequest(String path) throws IOException, InterruptedException {
        LOGGER.log(Level.WARNING, "Preparing request to URL: " + path);

        try (InputStream inputStream = getRequestAsInputStream(path)){
            return IOUtils.toString(inputStream, "UTF-8");
        }
    }

    private int headRequestStatus(String path) throws IOException, InterruptedException {
        HttpHead httpHead = new HttpHead(path);
        try(CloseableHttpResponse response = executeMethod(httpHead)) {
            EntityUtils.consume(response.getEntity());
            return response.getStatusLine().getStatusCode();
        } catch (IOException e) {
            throw new IOException("Communication error for url: " + path, e);
        } finally {
            release(httpHead);
        }
    }

    private void deleteRequest(String path) throws IOException, InterruptedException {
        HttpDelete httppost = new HttpDelete(path);
        try(CloseableHttpResponse response =  executeMethod(httppost)) {
            EntityUtils.consume(response.getEntity());
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException("URL: " + path);
            }
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_NO_CONTENT) {
                throw new BitbucketRequestException(response.getStatusLine().getStatusCode(), "HTTP request error. Status: " + response.getStatusLine().getStatusCode() + ": " + response.getStatusLine().getReasonPhrase());
            }
        } catch (BitbucketRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Communication error for url: " + path, e);
        } finally {
            release(httppost);
        }
    }

    private String doRequest(HttpRequestBase httppost) throws IOException, InterruptedException {
        try(CloseableHttpResponse response =  executeMethod(httppost)) {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NO_CONTENT) {
                EntityUtils.consume(response.getEntity());
                // 204, no content
                return "";
            }
            String content = getResponseContent(response);
            EntityUtils.consume(response.getEntity());
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK && response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
                throw new BitbucketRequestException(response.getStatusLine().getStatusCode(), "HTTP request error. Status: " + response.getStatusLine().getStatusCode() + ": " + response.getStatusLine().getReasonPhrase() + ".\n" + response);
            }
            return content;
        } catch (BitbucketRequestException e) {
            throw e;
        } catch (IOException e) {
            try {
                throw new IOException("Communication error for url: " + httppost.getURI(), e);
            } catch (IOException e1) {
                throw new IOException("Communication error", e);
            }
        } finally {
            release(httppost);
        }
    }

    private void release(HttpRequestBase method) {
        method.releaseConnection();
        connectionManager.closeExpiredConnections();
    }

    private String getResponseContent(CloseableHttpResponse response) throws IOException {
        String content;
        long len = response.getEntity().getContentLength();
        if (len == 0) {
            content = "";
        } else {
            ByteArrayOutputStream buf;
            if (len > 0 && len <= Integer.MAX_VALUE / 2) {
                buf = new ByteArrayOutputStream((int) len);
            } else {
                buf = new ByteArrayOutputStream();
            }
            try (InputStream is = response.getEntity().getContent()) {
                IOUtils.copy(is, buf);
            }
            content = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
        return content;
    }

    private String putRequest(String path, String content) throws IOException, InterruptedException  {
        HttpPut request = new HttpPut(path);
        request.setEntity(new StringEntity(content, ContentType.create("application/json", "UTF-8")));
        return doRequest(request);
    }

    private String postRequest(String path, String content) throws IOException, InterruptedException {
        HttpPost httppost = new HttpPost(path);
        httppost.setEntity(new StringEntity(content, ContentType.create("application/json", "UTF-8")));
        return doRequest(httppost);
    }

    private String postRequest(String path, List<? extends NameValuePair> params) throws IOException, InterruptedException {
        HttpPost httppost = new HttpPost(path);
        httppost.setEntity(new UrlEncodedFormEntity(params));
        return doRequest(httppost);
    }

    private List<BitbucketCloudBranch> getAllBranches(String response) throws IOException, InterruptedException {
        List<BitbucketCloudBranch> branches = new ArrayList<BitbucketCloudBranch>();
        BitbucketCloudPage<BitbucketCloudBranch> page = JsonParser.mapper.readValue(response,
                new TypeReference<BitbucketCloudPage<BitbucketCloudBranch>>(){});
        branches.addAll(page.getValues());
        while (!page.isLastPage()){
            response = getRequest(page.getNext());
            page = JsonParser.mapper.readValue(response,
                    new TypeReference<BitbucketCloudPage<BitbucketCloudBranch>>(){});
            branches.addAll(page.getValues());
        }

        // Filter the inactive branches out
        List<BitbucketCloudBranch> activeBranches = new ArrayList<>();
        for (BitbucketCloudBranch branch: branches) {
            if (branch.isActive()) {
                activeBranches.add(branch);
            }
        }

        return activeBranches;
    }

    public Iterable<SCMFile> getDirectoryContent(final BitbucketSCMFile parent) throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/src{/branchOrHash,path}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("branchOrHash", parent.getHash())
                .set("path", parent.getPath())
                .expand();
        List<SCMFile> result = new ArrayList<>();
        String response = getRequest(url);
        BitbucketCloudPage<BitbucketRepositorySource> page = JsonParser.mapper.readValue(response,
                new TypeReference<BitbucketCloudPage<BitbucketRepositorySource>>(){});

        for(BitbucketRepositorySource source:page.getValues()){
            result.add(source.toBitbucketScmFile(parent));
        }

        while (!page.isLastPage()){
            response = getRequest(page.getNext());
            page = JsonParser.mapper.readValue(response,
                    new TypeReference<BitbucketCloudPage<Map>>(){});
            for(BitbucketRepositorySource source:page.getValues()){
                result.add(source.toBitbucketScmFile(parent));
            }
        }
        return result;
    }

    public InputStream getFileContent(BitbucketSCMFile file) throws IOException, InterruptedException {
        String url = UriTemplate.fromTemplate(REPO_URL_TEMPLATE + "/src{/branchOrHash,path}")
                .set("owner", owner)
                .set("repo", repositoryName)
                .set("branchOrHash", file.getHash())
                .set("path", file.getPath())
                .expand();
        return getRequestAsInputStream(url);
    }
}
