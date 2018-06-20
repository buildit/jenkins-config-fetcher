package com.buildit.jenkins.configfetcher

import groovy.mock.interceptor.MockFor
import org.junit.*
import org.junit.rules.TemporaryFolder

import static org.hamcrest.CoreMatchers.equalTo
import static org.hamcrest.CoreMatchers.nullValue
import static com.buildit.jenkins.utils.FreePort.nextFreePort
import static com.buildit.jenkins.utils.GitRepository.initialiseGitRepository
import static com.buildit.jenkins.utils.HttpServer.withSecureServerAndBasicAuthentication
import static com.buildit.jenkins.utils.HttpServer.withServer
import static com.buildit.jenkins.utils.Zipper.zip

class ConfigFetcherTest {

    private static final String SECRET = "1fd8a05b02c749f4"

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    def configFetcher = new ConfigFetcher()
    def defaultJenkinsHome

    @Before
    void setUp(){
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_STARTUP_SECRET: SECRET,
                    JENKINS_CONFIG_FILE: ""].get(key)
        }

        File configFile = folder.newFile("jenkins_temp.config")
        defaultJenkinsHome = configFile.parent
        GroovySystem.metaClassRegistry.removeMetaClass(File.class)
    }

    @Test
    void shouldReturnSecretFromEnvironmentVariable(){
        String startupSecret = "244d7e29-9ae5-4ea1-afa6-4d3940f7de91"
        System.metaClass.static.getenv = { String secret ->
            return [JENKINS_STARTUP_SECRET: startupSecret].get(secret)
        }
        def result = configFetcher.secret([jenkinsHome: defaultJenkinsHome])
        Assert.assertThat(result as String, equalTo(startupSecret))
    }

    @Test
    void shouldReturnSecretFromDefaultJenkinsHomeKeyFile(){
        String secretKeyString = "123456-1234-1234-1234-123456789012"
        File jenkinsKey = folder.newFile("key")
        jenkinsKey.withWriter {  writer ->
            writer.write(secretKeyString)
        }

        def jenkinsHome = jenkinsKey.getParent()
        System.metaClass.static.getenv = { String secret ->
            return [:].get(secret)
        }
        System.metaClass.static.getenv = { String key ->
            return [:].get(key)
        }
        def result = configFetcher.secret([jenkinsHome: jenkinsHome])
        Assert.assertThat(result as String, equalTo(secretKeyString))
    }

    @Test
    void shouldReturnSecretFromFile(){
        String startupSecret = "244d7e29-9ae5-4ea1-afa6-4d3940f7de91"
        File secretFile = folder.newFile("secret.txt")
        secretFile.withWriter {  writer ->
            writer.write(startupSecret)
        }
        System.metaClass.static.getenv = { String secret ->
            return [JENKINS_STARTUP_SECRET_FILE: secretFile.absolutePath].get(secret)
        }
        def result = configFetcher.secret([jenkinsHome: defaultJenkinsHome])
        Assert.assertThat(result as String, equalTo(startupSecret))
    }

    @Test
    void shouldReturnSecretFromFileOverSecretFromEnvironment(){
        String startupSecretFromFile = "244d7e29-9ae5-4ea1-afa6-4d3940f7de92"
        File secretFile = folder.newFile("secret.txt")
        secretFile.withWriter {  writer ->
            writer.write(startupSecretFromFile)
        }
        System.metaClass.static.getenv = { String secret ->
            return [JENKINS_STARTUP_SECRET_FILE: secretFile.absolutePath].get(secret)
        }
        def result = configFetcher.secret([jenkinsHome: defaultJenkinsHome])
        Assert.assertThat(result as String, equalTo(startupSecretFromFile))
    }

    @Test
    void shouldReturnSecretFromKubernetesMount() {
        String startupSecret = UUID.randomUUID()

        System.metaClass.static.getenv = { String secret ->
            return [:].get(secret)
        }
        System.metaClass.static.getenv = { String key ->
            return [:].get(key)
        }

        def mockFile = new MockFor(File)
        mockFile.demand.exists{true}
        mockFile.demand.getText{startupSecret}

        mockFile.use {
            def result = configFetcher.secret([jenkinsHome: defaultJenkinsHome])
            Assert.assertThat(result as String, equalTo(startupSecret))
        }
    }

    @Test
    void shouldTrimSecretFromFile(){
        String secret = "244d7e29-9ae5-4ea1-afa6-4d3940f7de92"
        String startupSecretFromFile = """
                ${secret}
        """
        File secretFile = folder.newFile("secret.txt")
        secretFile.withWriter {  writer ->
            writer.write(startupSecretFromFile)
        }
        System.metaClass.static.getenv = { String string ->
            return [JENKINS_STARTUP_SECRET_FILE: secretFile.absolutePath].get(string)
        }
        def result = configFetcher.secret([jenkinsHome: defaultJenkinsHome])
        Assert.assertThat(result as String, equalTo(secret))
    }


    @Test
    void shouldReturnConfigUsingNameFromScriptsConfigRelativeToPath(){
        File configFile = folder.newFile("jenkins.config")
        def jenkinsHome = configFile.parent
        configFile.withWriter {  writer ->
            writer.write(
            '''credentials {
                nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
            }''')
        }
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
    }

    @Test
    void shouldReturnJenkinsHomeUsingJenkinsHomeEnvIfExists(){
        def jenkinsHome = "/var/some/directory"
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_HOME: jenkinsHome].get(key)
        }
        def result =  configFetcher.jenkinsHome()
        Assert.assertThat(result as String, equalTo(jenkinsHome))
    }

    @Test(expected = IllegalStateException.class)
    void shouldThrowExceptionWhenJenkinsHomeHasNotBeenSet(){
        def result =  configFetcher.jenkinsHome()
        Assert.assertThat(result as String, equalTo(jenkinsHome))
    }

    @Test
    void shouldReturnConfigUsingLocationFromEnvironmentVariable(){
        File configFile = folder.newFile("some_file.config")
        def jenkinsHome = configFile.parent
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: configFile.absolutePath,
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        configFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADI0DEf8VfI6ct++00Hq++S9mbT6pnK9zMqDDe8Q1cQ2t2AiIMw/udYsmCiIe8Cs=)', 'description':'bluemix credentials']
            }''')
        }
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.credentials.bluemix.username as String, equalTo("bluemix"))
    }

    @Test
    void shouldReturnConfigUsingLocationFromEnvironmentVariableOverLocationFromConfigFile(){
        File configFile = folder.newFile("some_other_file.config")
        def jenkinsHome = configFile.parent
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: configFile.absolutePath,
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        configFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    node=['username':'node', 'password':'b1gblue', 'description':'node credentials', 'type': 'SSH', 'privateKeyFile':'.ssh/id_rsa']
            }''')
        }
        
        
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.credentials.node.username as String, equalTo("node"))
    }

    @Test
    void shouldSuccessfullyParseConfigWithMultipleClosureBocks(){
        File configFile = folder.newFile("some_other_file.config")
        def jenkinsHome = configFile.parent
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: configFile.absolutePath,
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        configFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                        node=['username':'node', 'password':'b1gblue', 'description':'node credentials', 'type': 'SSH', 'privateKeyFile':'.ssh/id_rsa']
                    }
                    
                    jobdsl {
                        jobdsl=[url:"http://gerrit.sandbox.extranet.group/aoo-jenkins-jobs", targets:"jobs/**/*.groovy", branch:"*/master"]
                    }
                    
            ''')
        }
        
        
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.credentials.node.username as String, equalTo("node"))
        Assert.assertThat(result.jobdsl.jobdsl.url as String, equalTo("http://gerrit.sandbox.extranet.group/aoo-jenkins-jobs"))
    }

    @Test
    void shouldFetchConfigFromUrl(){
        String SERVER_PORT = nextFreePort(50000, 60000)
        String SERVER_ADDRESS = "http://localhost:${SERVER_PORT}"
        File jenkinsConfig = folder.newFile("jenkins.config")
        jenkinsConfig.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    node=['username':'node', 'password':'b1gblue', 'description':'node credentials', 'type': 'SSH', 'privateKeyFile':'.ssh/id_rsa']
            }''')
        }
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${SERVER_ADDRESS}/jenkins.config",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        
        
        withServer(folder.root.absolutePath, SERVER_PORT) {
            def result = configFetcher.getConfig([:])
            Assert.assertThat(result.credentials.node.username as String, equalTo("node"))
        }
    }

    @Test
    void shouldFetchConfigFromMultipleUrls(){
        String SERVER_PORT = nextFreePort(50000, 60000)
        String SERVER_ADDRESS = "http://localhost:${SERVER_PORT}"
        File jenkinsConfig1 = folder.newFile("jenkins-config-1.config")
        jenkinsConfig1.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    node=['username':'node', 'password':'b1gblue', 'description':'node credentials', 'type': 'SSH', 'privateKeyFile':'.ssh/id_rsa']
            }''')
        }
        File jenkinsConfig2 = folder.newFile("jenkins-config-2.config")
        jenkinsConfig2.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADI0DEf8VfI6ct++00Hq++S9mbT6pnK9zMqDDe8Q1cQ2t2AiIMw/udYsmCiIe8Cs=)', 'description':'bluemix credentials']
            }''')
        }
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${SERVER_ADDRESS}/jenkins-config-1.config,${SERVER_ADDRESS}/jenkins-config-2.config",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        
        
        withServer(folder.root.absolutePath, SERVER_PORT) {
            def result = configFetcher.getConfig([:])
            Assert.assertThat(result.credentials.node.username as String, equalTo("node"))
            Assert.assertThat(result.credentials.bluemix.username as String, equalTo("bluemix"))
        }
    }

    @Test
    void shouldFetchConfigFromZip(){
        String SERVER_PORT = nextFreePort(50000, 60000)
        String SERVER_ADDRESS = "http://localhost:${SERVER_PORT}"
        File jenkinsConfig1 = folder.newFile("jenkins-config-1.config")
        jenkinsConfig1.withWriter {  writer ->
            writer.write(
                    '''credentials {
                nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
            }''')
        }
        File jenkinsConfig2 = folder.newFile("jenkins-config-2.config")
        jenkinsConfig2.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADI0DEf8VfI6ct++00Hq++S9mbT6pnK9zMqDDe8Q1cQ2t2AiIMw/udYsmCiIe8Cs=)', 'description':'bluemix credentials']
            }''')
        }
        def zip = zip(folder.newFile("jenkins-config.zip").absolutePath, jenkinsConfig1.absolutePath, jenkinsConfig2.absolutePath)

        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${SERVER_ADDRESS}/${zip.name}",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        
        
        withServer(folder.root.absolutePath, SERVER_PORT){
            def result =  configFetcher.getConfig([:])
            Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
        }
    }

    @Test
    void shouldFetchConfigFromGit(){
        File jenkinsConfig1 = folder.newFile("jenkins-config-1.config")
        jenkinsConfig1.withWriter {  writer ->
            writer.write(
                    '''credentials {
                nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
            }''')
        }
        File jenkinsConfig2 = folder.newFile("jenkins-config-2.config")
        jenkinsConfig2.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADI0DEf8VfI6ct++00Hq++S9mbT6pnK9zMqDDe8Q1cQ2t2AiIMw/udYsmCiIe8Cs=)', 'description':'bluemix credentials']
            }''')
        }
        initialiseGitRepository(jenkinsConfig1.parent)
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${jenkinsConfig1.parent}/.git",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        
        
        def result =  configFetcher.getConfig([:])
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
    }

    @Test
    void shouldSetCorrectRemoteUrl() {
        def repositoryUrl = "https://github.com/buildit/jenkins-pipeline-libraries.git"
        def authenticatedUrl = "https://USERNAME:PASSWORD@github.com/buildit/jenkins-pipeline-libraries.git"

        def result = configFetcher.authenticatedUrl(repositoryUrl, "USERNAME", "PASSWORD")

        Assert.assertThat(result as String, equalTo(authenticatedUrl))
    }

    @Test
    void shouldNotIncludeUsernameAndPasswordIfUsernameIsMissing() {
        def repositoryUrl = "https://github.com/buildit/jenkins-pipeline-libraries.git"

        def result = configFetcher.authenticatedUrl(repositoryUrl, null, "PASSWORD")

        Assert.assertThat(result as String, equalTo(repositoryUrl))
    }

    @Test
    void shouldIncludeUsernameOnlyIfPasswordIsMissing() {
        def repositoryUrl = "https://github.com/buildit/jenkins-pipeline-libraries.git"
        def authenticatedUrl = "https://USERNAME@github.com/buildit/jenkins-pipeline-libraries.git"

        def result = configFetcher.authenticatedUrl(repositoryUrl, "USERNAME", null)

        Assert.assertThat(result as String, equalTo(authenticatedUrl))
    }

    @Test
    void shouldNotIncludeUsernameAndPasswordIfUrlIsSsh() {
        def repositoryUrl = "ssh://github.com/buildit/jenkins-pipeline-libraries.git"

        def result = configFetcher.authenticatedUrl(repositoryUrl, "USERNAME", "PASSWORD")

        Assert.assertThat(result as String, equalTo(repositoryUrl))
    }

    @Test
    void shouldFetchConfigFromGitWithUsernameAndPasswordAuthentication() {
        String SERVER_PORT = nextFreePort(50000, 60000)
        String SECURE_SERVER_ADDRESS = "https://localhost:${SERVER_PORT}"
        File jenkinsConfig = folder.newFile("jenkins-config.config")
        jenkinsConfig.withWriter { writer ->
            writer.write(
                    '''credentials {
                nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
            }''')
        }
        initialiseGitRepository(jenkinsConfig.parent)
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE        : "${SECURE_SERVER_ADDRESS}/.git",
                    JENKINS_CONFIG_GIT_USERNAME: "admin",
                    JENKINS_CONFIG_GIT_PASSWORD: "admin",
                    JENKINS_CONFIG_GIT_VERIFY_SSL: "false",
                    JENKINS_STARTUP_SECRET: SECRET
            ].get(key)
        }
        
        
        withSecureServerAndBasicAuthentication(jenkinsConfig.parent, SERVER_PORT) {
            def result = configFetcher.getConfig([:])
            Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
        }
    }

    @Test
    void shouldFetchConfigFromGitWithUsernameAndPasswordFromFileAuthentication() {
        String SERVER_PORT = nextFreePort(50000, 60000)
        String SECURE_SERVER_ADDRESS = "https://localhost:${SERVER_PORT}"
        File jenkinsConfig = folder.newFile("jenkins-config.config")
        jenkinsConfig.withWriter { writer ->
            writer.write(
                    '''credentials {
                nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
            }''')
        }
        initialiseGitRepository(jenkinsConfig.parent)
        File passwordFile = folder.newFile("JENKINS_CONFIG_GIT_PASSWORD")
        passwordFile.withWriter { writer ->
            writer.write('admin')
        }
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE        : "${SECURE_SERVER_ADDRESS}/.git",
                    JENKINS_CONFIG_GIT_USERNAME: "admin",
                    JENKINS_CONFIG_GIT_PASSWORD_FILE: passwordFile.absolutePath,
                    JENKINS_CONFIG_GIT_VERIFY_SSL: "false",
                    JENKINS_STARTUP_SECRET: SECRET
            ].get(key)
        }
        
        
        withSecureServerAndBasicAuthentication(jenkinsConfig.parent, SERVER_PORT) {
            def result = configFetcher.getConfig([:])
            Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
        }
    }

    @Test
    void shouldFetchConfigFromMultipleGitRepos(){
        File repo1 = folder.newFolder("repo1")
        repo1.mkdirs()
        File jenkinsConfig1 = new File("jenkins-config-1.config", repo1)
        jenkinsConfig1.withWriter {  writer ->
            writer.write(
                    '''credentials {
                nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
            }''')
        }
        initialiseGitRepository(repo1.absolutePath)
        File repo2 = folder.newFolder("repo2")
        repo2.mkdirs()
        File jenkinsConfig2 = new File("jenkins-config-2.config", repo2)
        jenkinsConfig2.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADI0DEf8VfI6ct++00Hq++S9mbT6pnK9zMqDDe8Q1cQ2t2AiIMw/udYsmCiIe8Cs=)', 'description':'bluemix credentials']
            }''')
        }
        initialiseGitRepository(repo2.absolutePath)
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${repo1}/.git,${repo2}/.git",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        
        
        def result =  configFetcher.getConfig([:])
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
        Assert.assertThat(result.credentials.bluemix.username as String, equalTo("bluemix"))

    }

    @Test
    void shouldFetchConfigFromGitBranch(){
        File jenkinsConfig1 = folder.newFile("jenkins-config-1.config")
        jenkinsConfig1.withWriter {  writer ->
            writer.write(
                    '''credentials {
                nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
            }''')
        }
        File jenkinsConfig2 = folder.newFile("jenkins-config-2.config")
        jenkinsConfig2.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADI0DEf8VfI6ct++00Hq++S9mbT6pnK9zMqDDe8Q1cQ2t2AiIMw/udYsmCiIe8Cs=)', 'description':'bluemix credentials']
            }''')
        }
        initialiseGitRepository(jenkinsConfig1.parent, "foo", "master")
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${jenkinsConfig1.parent}/.git#foo",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        
        
        def result =  configFetcher.getConfig([:])
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
    }

    @Test
    void shouldReturnConfigMergedFromMultipleFiles(){
        File configFile = folder.newFile("some_file.config")
        def jenkinsHome = configFile.parent
        configFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADNzFl3wctJJpmh7hBqhAtiKOhL/RFgCGmhHKKA9ANXWWRd9e)', 'description':'bluemix credentials']
            }''')
        }
        File secondConfigFile = folder.newFile("some_other_file.config")
        secondConfigFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    nexus=['username':'nexus', 'password':'ENC(AAAADF0Ck5vLU+A15pxvRrg4saLY7pon03byhmI1rzUx5HkWiA==)', 'description':'nexus credentials']
            }''')
        }
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${configFile.absolutePath},${secondConfigFile.absolutePath}",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.credentials.bluemix.username as String, equalTo("bluemix"))
        Assert.assertThat(result.credentials.bluemix.password as String, equalTo("b1gblue"))
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
        Assert.assertThat(result.credentials.nexus.password as String, equalTo("n3xus"))

    }

    @Test
    void shouldOverrideConfigInOrderListed(){
        File configFile = folder.newFile("some_file.config")
        def jenkinsHome = configFile.parent
        configFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADI0DEf8VfI6ct++00Hq++S9mbT6pnK9zMqDDe8Q1cQ2t2AiIMw/udYsmCiIe8Cs=)', 'description':'bluemix credentials']
            }''')
        }
        File secondConfigFile = folder.newFile("some_other_file.config")
        secondConfigFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                    bluemix=['username':'other_bluemix_user', 'password':'ENC(AAAADOsY16PsHLN7+oitJpqemYjfLfns7KaKsmrMvUge5pF+bqQrhW8=)', 'description':'bluemix credentials']
            }''')
        }
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_CONFIG_FILE: "${configFile.absolutePath},${secondConfigFile.absolutePath}",
                    JENKINS_STARTUP_SECRET: SECRET].get(key)
        }

        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.credentials.bluemix.username as String, equalTo("other_bluemix_user"))
        Assert.assertThat(result.credentials.bluemix.password as String, equalTo("smallblu3"))

    }

    @Test
    void shouldReturnNullWhenNoSecretIsSet(){
        System.metaClass.static.getenv = { String secret ->
            return [:].get(secret)
        }
        def result = configFetcher.secret()
        Assert.assertThat(result as String, nullValue())
    }

    @Test
    void shouldDecryptSimpleString(){
        def result = configFetcher.decryptConfigValues("ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)", SECRET)
        Assert.assertThat(result as String, equalTo("somes3cret"))
    }

    @Test
    void shouldMatchCaseOfEncryptedStringIndicator(){
        def result = configFetcher.decryptConfigValues("enc(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)", SECRET)
        Assert.assertThat(result as String, equalTo("enc(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)"))
    }

    @Test
    void shouldOnlyAttemptToDecryptIfPatternIsComplete(){
        def result = configFetcher.decryptConfigValues("ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H", SECRET)
        Assert.assertThat(result as String, equalTo("ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H"))
    }

    @Test
    void shouldDecryptComplexStringWithMultipleEncryptedValues(){
        def result = configFetcher.decryptConfigValues(stripWhitespace(
                '''credentials {
                    nexus=['username':'nexus', 'password':'ENC(AAAADFjPyMqnKecSFI8OeImPdxuDuvTf7mDaGDHFcTtFeLEOrUwCWJ1H)', 'description':'nexus credentials']
                    bluemix=['username':'bluemix', 'password':'ENC(AAAADN8r+EijhaZOD49ksWKSlknb/0ldvuGDD8GjjowSs2ND2oTM)', 'description':'bluemix credentials']
                    node=['username':'node', 'password':'ENC(AAAADN8r+EijhaZOD49ksWKSlknb/0ldvuGDD8GjjowSs2ND2oTM)', 'description':'node credentials', 'type': 'SSH', 'privateKeyFile':'.ssh/id_rsa']
                }''')
                , SECRET)
        Assert.assertThat(result as String, equalTo(stripWhitespace(
                '''credentials {
                    nexus=['username':'nexus', 'password':'somes3cret', 'description':'nexus credentials']
                    bluemix=['username':'bluemix', 'password':'b1gblue', 'description':'bluemix credentials']
                    node=['username':'node', 'password':'b1gblue', 'description':'node credentials', 'type': 'SSH', 'privateKeyFile':'.ssh/id_rsa']
                }''')
        ))
    }

    @Test
    @Ignore("Will add note to docs rather than fix this for now - needs a good regex")
    void shouldHandleEncryptedValuesWithLineBreaks(){
        File configFile = folder.newFile("jenkins.config")
        def jenkinsHome = configFile.parent
        configFile.withWriter {  writer ->
            writer.write(
                    '''credentials {
                nexus=['username':'nexus', 'password':'ENC(+01gNIV78CozFW97ZMDM/w==)', 'description':'nexus credentials']
            }''')
        }
        
        
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
    }

    @Test
    void shouldAccountForTripleQuotedValues(){
        File configFile = folder.newFile("jenkins.config")
        configFile.withWriter {  writer ->
            writer.write(
                    """credentials {
                nexus=['username':'nexus', 'password':'''ENC(AAAADFjPyMqnKecSFI8OeImPdxuDu
vTf7mDaGDHFcTtFeLEOrUwCWJ1H)''', 'description':'nexus credentials']
            }""")
        }
        
        
        def result =  configFetcher.getConfig([jenkinsHome: configFile.parent])
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
    }

    @Test
    void shouldAccountForTripleQuotedValuesWithSpaces(){
        File configFile = folder.newFile("jenkins.config")
        configFile.withWriter {  writer ->
            writer.write(
                    """credentials {
                nexus=['username':'nexus', 'password':'''ENC(AAAADFjPyMqnKecSFI8OeImPdxuDu
                    vTf7mDaGDHFcTtFeLEOrUwCWJ1H)''', 'description':'nexus credentials']
            }""")
        }


        def result =  configFetcher.getConfig([jenkinsHome: configFile.parent])
        Assert.assertThat(result.credentials.nexus.username as String, equalTo("nexus"))
    }

    @Test
    void shouldReplaceJenkinsHomeTemplateValueWithActualValue(){
        File configFile = folder.newFile("jenkins.config")
        def jenkinsHome = configFile.parent
        configFile.withWriter {  writer ->
            writer.write('''
                files {
                    privateKey=[
                            path: '${jenkinsHome}/.ssh/id_rsa'
                        ]
                }
            ''')
        }
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.files.privateKey.path as String, equalTo("${jenkinsHome}/.ssh/id_rsa" as String))
    }

    @Test
    void shouldIgnoreMissingTemplateValues(){
        File configFile = folder.newFile("jenkins.config")
        def jenkinsHome = configFile.parent
        configFile.withWriter {  writer ->
            writer.write('''
                files {
                    privateKey=[
                            path: '$HOME/.ssh/id_rsa'
                        ]
                }
            ''')
        }
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.files.privateKey.path as String, equalTo('$HOME/.ssh/id_rsa' as String))
    }

    @Test
    void shouldStripBracesAndIgnoreMissingTemplateValues(){
        File configFile = folder.newFile("jenkins.config")
        def jenkinsHome = configFile.parent
        configFile.withWriter {  writer ->
            writer.write('''
                files {
                    privateKey=[
                            path: '${HOME}/.ssh/id_rsa'
                        ]
                }
            ''')
        }
        def result =  configFetcher.getConfig([jenkinsHome: jenkinsHome])
        Assert.assertThat(result.files.privateKey.path as String, equalTo('$HOME/.ssh/id_rsa' as String))
    }

    @Test
    void shouldReturnJenkinsHome(){
        def jenkinsHome = '/var/jenkins'
        System.metaClass.static.getenv = { String key ->
            return [JENKINS_HOME: jenkinsHome].get(key)
        }
        def result =  configFetcher.jenkinsHome()
        Assert.assertThat(result as String, equalTo(jenkinsHome))
    }

    @Test(expected = IllegalStateException.class)
    void shouldThrowExceptionWhenJenkinsHomeNotFound(){
        def result =  configFetcher.jenkinsHome()
        Assert.assertThat(result as String, equalTo(jenkinsHome))
    }

    @Test
    void shouldReturnJenkinsHomeFromDefaultLinuxPropertiesFile(){
        def jenkinsHome = '/var/jenkins'
        File configFile = folder.newFile("jenkins")
        configFile.withWriter {  writer ->
            writer.write("""
                JENKINS_HOME=${jenkinsHome}
            """)
        }
        File.metaClass.constructor = { String path ->
            if(path == '/etc/default/jenkins') {
                return configFile
            } else{
                // return file that doesn't exist
                def dummy = folder.newFile("temp")
                dummy.delete()
                return dummy
            }
        }
        def result =  configFetcher.jenkinsHome()
        Assert.assertThat(result as String, equalTo(jenkinsHome))
    }

    @Test
    void shouldReturnJenkinsHomeFromDefaultRedHatPropertiesFile(){
        def jenkinsHome = '/var/jenkins'
        File configFile = folder.newFile("jenkins")
        configFile.withWriter {  writer ->
            writer.write("""
                JENKINS_HOME=${jenkinsHome}
            """)
        }
        File.metaClass.constructor = { String path ->
            if(path == '/etc/sysconfig/jenkins'){
                return configFile
            } else{
                // return file that doesn't exist
                def dummy = folder.newFile("temp")
                dummy.delete()
                return dummy
            }
        }
        def result =  configFetcher.jenkinsHome()
        Assert.assertThat(result as String, equalTo(jenkinsHome))
    }

    def stripWhitespace(String string){
        string.replaceAll("\\s","")
    }
}