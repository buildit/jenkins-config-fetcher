package com.buildit.jenkins.configfetcher

import groovy.text.SimpleTemplateEngine

import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

import static com.buildit.encryptor.Encryptor.decrypt

class ConfigFetcher {

    static final DEFAULT_FILE = 'jenkins.config'
    static final CONFIGFILE_SUFFIX = ['.config']
    static final KUBERNETES_SECRET_PATH = '/var/run/jenkins/startup-secret'

    def fetch(){
        return getConfig([jenkinsHome: jenkinsHome()])
    }

    def jenkinsHome(){
        if(System.getenv("JENKINS_HOME")){
            return System.getenv("JENKINS_HOME")
        }
        if(new File('/etc/default/jenkins').exists()){
            return readProperties('/etc/default/jenkins').getProperty('JENKINS_HOME')
        }
        if(new File('/etc/sysconfig/jenkins').exists()){
            return readProperties('/etc/sysconfig/jenkins').getProperty('JENKINS_HOME')
        }
        throw new IllegalStateException("JENKINS_HOME value cannot be found")
    }

    private Properties readProperties(String file){
        Properties props = new Properties()
        File propsFile = new File(file)
        props.load(propsFile.newDataInputStream())
        return props
    }

    String secret(bindingVariables) {
        def secret

        def secretFileLocationString = System.getenv("JENKINS_STARTUP_SECRET_FILE") ? System.getenv("JENKINS_STARTUP_SECRET_FILE") : null
        if (secretFileLocationString) {
            def secretFile = new File(System.getenv("JENKINS_STARTUP_SECRET_FILE").toString())
            secret = secretFile.exists() ? secretFile.text.trim() : ""
        }
        if (!secret) {
            secret = System.getenv("JENKINS_STARTUP_SECRET")
        }

        if (!secret && bindingVariables) {
            def secretFile = new File("${bindingVariables.jenkinsHome}/key")
            secret = secretFile.exists() ? secretFile.text : null
        }
        if (!secret) {
            def secretFile = new File(KUBERNETES_SECRET_PATH)
            secret = secretFile.exists() ? secretFile.text.trim() : null
        }
        if (!secret) {
            println("NOTE. No decryption secret has been set.")
        }
        return secret
    }

    Object getConfig(bindingVariables) {
        def secret = secret(bindingVariables)

        def configStrings = configStrings(bindingVariables)
        def config = initialiseConfig()
        configStrings.each {
            config.merge(parse(it, secret, bindingVariables))
        }
        return config
    }

    private Serializable configStrings(bindingVariables) {
        def configFileLocationString = System.getenv("JENKINS_CONFIG_FILE") ? System.getenv("JENKINS_CONFIG_FILE") : null

        if (!configFileLocationString) {
            configFileLocationString = new File("${bindingVariables.jenkinsHome}/${DEFAULT_FILE}")
        }

        def configStrings = []
        def configFileList = listFiles(configFileLocationString)
        configFileList.toString().tokenize(",").findAll { CONFIGFILE_SUFFIX.contains(getSuffix(it)) }.each {
            configStrings.addAll(toString(it as String))
        }
        return configStrings
    }

    final String getSuffix(string){
        List bits = string.tokenize(".")
        return ".${bits.size() > 1 ? bits.get(bits.size()-1) : ''}"
    }

    def parse(configString, secret, bindingVariables){
        println("Config is : ${configString}")
        def unencryptedConfigString = decryptConfigValues(configString as String, secret as String)
        def boundConfigString = bindTemplateValues(unencryptedConfigString, bindingVariables)
        def config = new ConfigSlurper().parse(boundConfigString)
        return config
    }

    def bindTemplateValues(configString, binding){
        Map m = [:].withDefault { key -> return "\$${key}" }
        m.putAll(binding as Map)
        return new SimpleTemplateEngine().createTemplate(configString).make(m as Map).toString()
    }

    def decryptConfigValues(String config, String secret){
        def matcher = config =~ /(?s)ENC\(([^\)]*)\)(?)/
        StringBuffer sb = new StringBuffer(config.length())
        while (matcher.find()) {
            matcher.groupCount()
            def match = matcher.group(1)
            def unencrypted = decryptPassword(match, secret)
            matcher.appendReplacement(sb, unencrypted)
        }
        matcher.appendTail(sb)
        return sb.toString().trim()
    }

    private decryptPassword(password, secret) {
        decrypt(secret, password)
    }

    def initialiseConfig(){
        File tmp = File.createTempFile("temp",".tmp")
        new ConfigSlurper().parse(tmp.toURI().toURL())
    }

    def toString(String location){
        String result
        if(isURL(location)) {
            result = new URL(location).text
        }else{
            result = (!new File(location).exists() || new File(location).isDirectory()) ? "" : new File(location).text
        }
        return result
    }

    def isURL(string){
        try{
            new URL(string as String)
        }catch (MalformedURLException){
            return false
        }
        return true
    }

    def isGit(String string){
        return string.endsWith(".git") || string.contains(".git#")
    }

    def listFiles(location){
        def results = []
        location.toString().tokenize(",").each {
            if(isGit(it)) {
                results.addAll(checkout(it))
            }else if (isZip(it)) {
                results.addAll(unzip(new URL(it).openStream()))
            }else{
                results.add(it)
            }
        }
        return results.join(",")
    }

    def isZip(String location){
        return new ZipInputStream(isURL(location) ? new URL(location).openStream() : new File(location).exists() ? new FileInputStream(location) :  new ByteArrayInputStream()).getNextEntry() != null
    }

    def checkout(String repository){
        String destDir = createTempDirectory().absolutePath
        List bits = repository.tokenize("#")
        String username = System.getenv("JENKINS_CONFIG_GIT_USERNAME") ?: ""
        String password = fileOrValueEnv("JENKINS_CONFIG_GIT_PASSWORD_FILE", "JENKINS_CONFIG_GIT_PASSWORD") ?: ""
        String url = authenticatedUrl(bits.get(0), username, password)
        String branch = bits.size() > 1 ? bits.get(1) : "master"
        String options = ""
        if(System.getenv("JENKINS_CONFIG_GIT_VERIFY_SSL") && System.getenv("JENKINS_CONFIG_GIT_VERIFY_SSL")=="false"){
            options = "${options} http.sslVerify=false"
        }
        String command = "git ${options.length() > 0 ? '-c ' + options : ''} clone -b ${branch} ${url} ${destDir}"
        logSafe("executing command: $command", [username, password, urlEncode(username), urlEncode(password)])
        execute(command, false)
        return walk(destDir as String)
    }

    String fileOrValueEnv(String fileEnv, String valueEnv) {
        def result

        def location = System.getenv(fileEnv) ? System.getenv(fileEnv) : null

        if (!nullOrEmpty(location)) {
            def file = new File(location)
            result = file.exists() ? file.text : ""
        }

        if (nullOrEmpty(result)) {
            result = System.getenv(valueEnv)
        }

        return result
    }

    def logSafe(string, toObscure){
        def line = string
        toObscure.each{
            if(!nullOrEmpty(it)){
                line = "${line.replaceAll(it, '*************')}"
            }
        }
        println(line)
    }

    def authenticatedUrl(url, username, password){
        if((nullOrEmpty(username) || isSsh(url))){
            return url
        }
        def encodedUsername = urlEncode(username)
        def encodedPassword = urlEncode(password)
        def bits = (url as String).split("://")
        def authenticatedUrl = "${encodedUsername}:${encodedPassword}@" + bits[0] as String
        if(bits.length == 2){
            authenticatedUrl = bits[0] + "://${encodedUsername}:${encodedPassword}@" + bits[1] as String
        }
        return authenticatedUrl.replace(":@", "@")
    }

    def urlEncode(string){
        return nullOrEmpty(string) ? "" : URLEncoder.encode(string as String, "UTF-8")
    }

    def isSsh(string){
        return (string as String).startsWith("ssh")
    }

    private File createTempDirectory() {
        File dir = new File("${System.getProperty("java.io.tmpdir")}/${UUID.randomUUID().toString()}")
        if(!dir.exists()) dir.mkdirs()
        return dir
    }

    def unzip(InputStream inputStream) {
        String destDir = createTempDirectory()
        byte[] buffer = new byte[1024]
        try {
            ZipInputStream zis = new ZipInputStream(inputStream)
            ZipEntry ze = zis.getNextEntry()
            while(ze != null){
                String fileName = ze.getName()
                File newFile = new File(destDir + File.separator + fileName)
                System.out.println("Unzipping to "+newFile.getAbsolutePath())
                new File(newFile.getParent()).mkdirs()
                FileOutputStream fos = new FileOutputStream(newFile)
                int len
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len)
                }
                fos.close()
                zis.closeEntry()
                ze = zis.getNextEntry()
            }
            zis.closeEntry()
            zis.close()
            inputStream.close()
            return walk(destDir as String)
        } catch (IOException e) {
            e.printStackTrace()
        }
    }

    def walk(String path) {
        List<String> allFiles = new ArrayList<String>()
        Queue<File> dirs = new LinkedList<File>()
        dirs.add(new File(path))
        while (!dirs.isEmpty()) {
            for (File f : dirs.poll().listFiles()) {
                if (f.isDirectory()) {
                    dirs.add(f)
                } else if (f.isFile()) {
                    allFiles.add(f.absolutePath)
                }
            }
        }
        return allFiles
    }

    private execute(String command, boolean logCommand=true) {
        return execute(command, new File(System.properties.'user.dir'), logCommand)
    }

    private execute(String command, File workingDir, logCommand=true) {
        if(logCommand){
            println("executing command: ${command}")
        }
        def process = new ProcessBuilder(addShellPrefix(command))
                .directory(workingDir)
                .redirectErrorStream(logCommand)
                .inheritIO()
                .start()
        process.inputStream.eachLine {println it}
        process.waitFor()
        println("exit value: ${process.exitValue()}")
        if(process.exitValue() > 0){
            throw new RuntimeException("Process exited with non-zero value: ${process.exitValue()}")
        }
    }

    private addShellPrefix(String command) {
        def commandArray = new String[3]
        commandArray[0] = "sh"
        commandArray[1] = "-c"
        commandArray[2] = command
        return commandArray
    }

    def nullOrEmpty(string){
        return !string || string.toString().length() == 0
    }

    def baseDirectory(){
        File thisScript = new File(getClass().protectionDomain.codeSource.location.path)
        return thisScript.getParent()
    }


}
