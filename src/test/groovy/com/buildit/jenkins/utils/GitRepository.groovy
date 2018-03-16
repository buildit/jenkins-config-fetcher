package com.buildit.jenkins.utils

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

class GitRepository {

    static initialiseGitRepository(workingDirectory, branch="master", switchTo="master"){
        Repository repo = FileRepositoryBuilder.create(new File(workingDirectory, ".git"))
        repo.create()
        Git git = new Git(repo)
        git.commit().setAuthor("test", "test@test.com").setMessage("init").call()
        if(branch != "master"){
            git.branchCreate().setName(branch as String).call()
        }
        git.checkout().setName(branch as String).call()
        git.add().addFilepattern(".").call()
        git.commit().setAuthor("test", "test@test.com").setMessage("first commit").call()
        git.checkout().setName(switchTo as String).call()
        new Commander().execute("git update-server-info", new File(workingDirectory))
    }

    static clone(String url, branch, directory, username, password){
        Repository repository = FileRepositoryBuilder.create(new File(directory, ".git"))
        repository.create()
        Git git = new Git(repository)
        StoredConfig config = git.getRepository().getConfig()
        config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin", ConfigConstants.CONFIG_KEY_URL, url)
        config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin", ConfigConstants.CONFIG_FETCH_SECTION, "+refs/heads/*:refs/remotes/origin/*")

        config.setBoolean("http", null, "sslVerify", false)
        config.save()


        def fetch = git.fetch().setRemote("origin")
        fetch.call()

        git.branchCreate().setName(branch).setStartPoint("origin/" + branch).call();
        git.checkout().setName(branch).call()


    }

    static void main(String[] args){
        clone("https://github.com/buildit/hashicorp-vault-credentials-plugin.git", "master", createTempDirectory().absolutePath, "", "")
    }

    static File createTempDirectory() {
        File dir = new File("${System.getProperty("java.io.tmpdir")}/${UUID.randomUUID().toString()}")
        if(!dir.exists()) dir.mkdirs()
        println(dir.absolutePath)
        return dir
    }
}
