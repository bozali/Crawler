package main;

import Models.BuildSystem;
import Models.RMetaData;
import com.google.gson.*;
import org.eclipse.egit.github.core.*;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.ContentsService;
import org.eclipse.egit.github.core.service.RepositoryService;
import utils.Curl;
import utils.JsonWriter;
import java.io.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Main GitHub Crawler class. Queries, filters and stores the GitHub repositories.
 *
 * @author Daniel Braun
 */
public class GitHubCrawler {
    /**
    * The filtered programming language.
    */
    private String searchLanguage;
    /**
     * The BuildSystem to detect and filter for.
     */
    private BuildSystem buildSystem;
    /**
     * The users login name to GitHub
     */
    private String username;
    /**
     * The users login password to GitHub.
     */
    private String password;
    /**
     * The GitHub client object.
     */
    private GitHubClient client;
    private String lastPushedDate;
    private int maxStars = Integer.MAX_VALUE;
    private int starDecreaseAmount;
    private int counter = 0;
    private boolean foundRepoInLastQuery;
    private boolean notFirstQuery = false;
    /**
     * Crawlers Constructor.
     * @param language The programming language filter.
     * @param buildSystem The build system filter.
     * @param username The Github user name.
     * @param password The Github user password.
     */
    public GitHubCrawler(String language, String lastPushedDate, String starsDecreaseAmount ,BuildSystem buildSystem, String username, String password){
        this.searchLanguage = language;
        this.lastPushedDate = lastPushedDate;
        this.buildSystem = buildSystem;
        this.username = username;
        this.password = password;
        this.client = authenticate(username, password);

        try {
            this.starDecreaseAmount = Integer.parseInt(starsDecreaseAmount);
        } catch (NumberFormatException e) {
            System.err.println("starsDecreaseAmount not an integer. Config file not properly set up.\nShutting down.");
            System.exit(1);
        }
    }

    /**
     * The main entry point to start the crawler.
     */
    public void run() {

        while(true) {
            filterRepositories(getRepositoryService(client), buildSearchQuery());
        }
    }

    /**
     * Function to authenticate to the GitHub client. Authenticated user have 5000 request per hour.
     * Not authenticated users have 60 requests per hour.
     *
     * @param user The Github user name.
     * @param password The Github user password.
     * @return Either an authenticated ot not authenticated GithubClient.
     */
    private GitHubClient authenticate(String user, String password) {
        GitHubClient client = new GitHubClient();
        try {
            client.setCredentials(user, password);
        } catch (Exception e) {
            System.out.println("Wrong username or Password!");
        }
        return client;
    }

    private RepositoryService getRepositoryService(GitHubClient client) {
        return new RepositoryService(client);
    }

    private Map<String, String> buildSearchQuery() {
        Map<String, String> searchQuery = new HashMap<String, String>();
        searchQuery.put("language", searchLanguage); //Search for repos with given searchlLanguage set in the config file
        searchQuery.put("is", "public"); //Search for repos that are public
        searchQuery.put("pushed", ">=" + lastPushedDate); // The pushed qualifier will return a list of repositories, sorted by the most recent commit made on any branch in the repository.
        searchQuery.put("sort", "stars");

        if(maxStars != Integer.MAX_VALUE && maxStars > 0 && foundRepoInLastQuery) { // && foundRepoInLastQuery
            maxStars = maxStars - 1; // mir entfallen nur die conan proj die von der letzten query genau maxStars hatten und hinter den 1000 results lagen
            System.out.println("Querying repositories with maximum number of stars of '" + maxStars + "' from last repository of previous query.");
            searchQuery.put("stars", "<=" + (maxStars));
        } else if(!foundRepoInLastQuery && maxStars != Integer.MAX_VALUE) { //
            System.out.println("No repository was found within the last 1000 crawled repositories.\nDecreasing the current stars count of "+maxStars+" by "+starDecreaseAmount+".");
            maxStars = maxStars - starDecreaseAmount;
            searchQuery.put("stars", "<=" + maxStars);
        } else if(!foundRepoInLastQuery && maxStars == Integer.MAX_VALUE && notFirstQuery) {
            //NOTE: This case is ignored. If we do not find any popular repository within the first 1000 repositories that uses conan,
            //then it would be unnecessary to crawl either for conan or on github for it.
            System.err.println("No popular repository was found within the first query without a stars limit.\nShutting Down.");
            System.err.println("NOTE: This case is ignored. If we do not find any popular repository that uses conan within the first 1000 repositories, " +
                    "then it would be unnecessary to crawl either for conan or on github for it.");
            System.exit(1);
        }
        notFirstQuery = true;
        foundRepoInLastQuery = false;
        if(maxStars <= 0) {
            //including 0 otherwise there is no other termination, due to the case that when the stars count reaches 0 and the query finds repositories,
            // it will set the stars count again to 0, resulting to the same query in a loop.
            System.out.println("Minimum value for stars reached. | STARS COUNT: " + maxStars);
            System.out.println("Crawling finished.\nShutting down");
            System.exit(0);
        }
        return searchQuery;
    }

    private List<SearchRepository> queryRepositories(RepositoryService service, Map<String, String> searchQuery, int page){
        try {
            Thread.sleep(1500);
            return service.searchRepositories(searchQuery, page);
        } catch (IOException | InterruptedException e) {
            System.err.println("Something went wrong while performing the repository search request.\nAborting.\n");
            System.err.println(e.getMessage());
            System.exit(1);
        }
        return null;
    }

    private Repository queryRepoByOwnerAndName(RepositoryService service, SearchRepository searchRepository) {
        try {
            Thread.sleep(1500);
            return service.getRepository(searchRepository.getOwner(), searchRepository.getName());
        } catch(IOException | InterruptedException e ) {
            System.err.println("Something went wrong while getting the Repository by Owner and repository Name. Skipping to next repository.");
            System.err.println(e.getMessage());
        }
        return null;
    }

    private void filterRepositories(RepositoryService service, Map<String, String> searchQuery) {

        for (int page = 1; page <= 10; page++) {

            List<SearchRepository> searchRepositoryResponse = queryRepositories(service, searchQuery, page);

            if (searchRepositoryResponse.isEmpty()) { // If we reached a page number that returns no repositories (empty list) in the query.
                System.out.println("Found " + searchRepositoryResponse.size() + " Repos by search at page " + page);
                System.out.println("Crawling Finished.\nShutting down.");
                System.exit(0);
                break;
            } else {

                System.out.println("Query Response:\nNumber Repos: " + searchRepositoryResponse.size() + "\nOn page " + page + ".\n");

                for (SearchRepository searchRepository : searchRepositoryResponse) {

                    Repository repositoryOfOwnerAndName = queryRepoByOwnerAndName(service, searchRepository);
                    maxStars = repositoryOfOwnerAndName.getWatchers();
                    System.err.println("Current maximum stars count: " + maxStars);
                    //Detect BuildSystem subroutine
                    BuildSystem foundBuildSystem = getFileContentsAtRootDir(repositoryOfOwnerAndName); // detectBuildSystem(repositoryOfOwnerAndName);
                    if (repositoryOfOwnerAndName != null) {
                        if (foundBuildSystem == buildSystem) { //BuildSystem was detected. Create a new RMetaData object and store all information
                            System.out.println("-----------------------------------------------");
                            counter ++;
                            foundRepoInLastQuery = true;
                            System.err.println("Overall detected repos: " + counter);
                            RMetaData metaDataObject = createRMetaDataObject(repositoryOfOwnerAndName, foundBuildSystem);
                            JsonWriter.getInstance().writeRepositoryToJson(metaDataObject);
                        }
                        System.out.println("-----------------------------------------------");
                        System.out.println("Request Limit: " + client.getRequestLimit());
                        System.out.println("Remaining Request: " + client.getRemainingRequests());
                    }
                }
            }
        }
        System.out.println("Maximum number of 1000 repositories were processed within one search query.\nSkipping others due to limitation.");
    }

    private RMetaData createRMetaDataObject(Repository repository, BuildSystem buildSystem) {
        RMetaData meteDataObject = new RMetaData();
        //Set all crawled fields
        meteDataObject.setId(repository.getId());
        meteDataObject.setName(repository.getName());
        meteDataObject.setOwner(repository.getOwner().getLogin());
        meteDataObject.setOwnerType(repository.getOwner().getType());
        meteDataObject.setDescription(repository.getDescription());
        meteDataObject.setLanguage(repository.getLanguage());
        meteDataObject.setHasDownloads(repository.isHasDownloads());
        meteDataObject.setSize(repository.getSize());
        meteDataObject.setPushedAt(repository.getPushedAt());
        meteDataObject.setCreatedAt(repository.getCreatedAt());
        meteDataObject.setDefaultBranch(repository.getMasterBranch());
        meteDataObject.setLatestCommitId(getLatestCommitId(repository));
        meteDataObject.setPrivate(repository.isPrivate());
        meteDataObject.setForksCount(repository.getForks());
        meteDataObject.setOpenIssuesCount(repository.getOpenIssues());
        meteDataObject.setStargazersCount(repository.getWatchers()); // NOTE: stargazers and watchers count are the same since 2012.
                                                                     // The counter now only increases if a project is starred.
                                                                     // SEE https://developer.github.com/changes/2012-09-05-watcher-api/
        meteDataObject.setHtmlUrl(repository.getHtmlUrl());
        meteDataObject.setCloneUrl(repository.getCloneUrl());
        meteDataObject.setBuildSystem(buildSystem.toString());
        meteDataObject.setBuildFilePath(buildSystem.getFilePaths());
        //Setting default values
        meteDataObject.setBuildStatus("UNKNOWN");
        meteDataObject.setErrorMessage(new ArrayList<>());
        meteDataObject.setPackageDependencies(new ArrayList<>());

        return meteDataObject;
    }

    /**
     * Detects if the repository contains specific build files required by the currently searched build system.
     *
     * @param repository The repository to detect the build system from
     * @return The detected BuildSystem
     */
    private BuildSystem getFileContentsAtRootDir(Repository repository) {
        ContentsService contentsService = new ContentsService(client);
        BuildSystem detectedBuildSystem = BuildSystem.UNKNOWN;
        List<String> filePaths = new ArrayList<>();

        try {
            List<RepositoryContents> repositoryContents = contentsService.getContents(repository);
            switch (buildSystem) { //using switch as it is easy to extend with further buildsystems to detect by adding more custom cases.
                case CMAKE:
                    if((repositoryContents.stream().anyMatch(o -> o.getName().equals("conanfile.py"))
                        || repositoryContents.stream().anyMatch(o -> o.getName().equals("conanfile.txt")))
                        && repositoryContents.stream().anyMatch(o -> o.getName().equals("CMakeLists.txt"))) { //When checking for the existence of a CMakeLists.txt file,
                                                                                                              //the [generator] defined in the conanfile will most likely be set to CMake
                                                                                                              //Thus we make an assumption that the generator is always set to CMake in the conanfile!
                        if(repositoryContents.stream().anyMatch(o -> o.getName().equals("conanfile.py")))
                            filePaths.add("conanfile.py");
                        else
                            filePaths.add("conanfile.txt");
                        detectedBuildSystem = BuildSystem.CMAKE;
                    }
                    break;
            }
        } catch (IOException e) {
            System.err.println("Something went wrong while querying the repository contents.\n");
            System.err.println(e.getMessage());
        }
        detectedBuildSystem.setFilePaths(filePaths);
        return detectedBuildSystem;
    }

    /**
     * Queries the GitHub v3 Commit model of the default master branch.
     *
     * @param repository The repository we are currently looking at.
     * @return A json response from the Github v3 endpoint.
     */
    private JsonObject queryCommitsOnDefaultMasterBranch(Repository repository) {
        JsonObject jsonResponse = null;
        try {
            Thread.sleep(1500);
            String stringResponse = Curl.getHTML("https://api.github.com/repos/" + repository.getOwner().getLogin() + "/" + repository.getName() + "/commits/" + repository.getMasterBranch());
            jsonResponse = new JsonParser().parse(stringResponse).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("Something went wrong while querying the repository commits.\n");
            System.err.println(e.getMessage());
        }
        return jsonResponse;
    }

    /**
     * Gets the latest commit id (sha) from the json Response.
     *
     * @param repository The repository we are currently looking at.
     * @return The latest commit id as a String.
     */
    private String getLatestCommitId(Repository repository){
        String latestCommitId = "";
        JsonObject commits = queryCommitsOnDefaultMasterBranch(repository);
        if(commits.has("sha"))
            latestCommitId = commits.get("sha").getAsString();

        return latestCommitId;
    }
}
