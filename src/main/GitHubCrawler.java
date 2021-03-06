package main;

import Models.BuildSystem;
import Models.RMetaData;
import com.google.common.util.concurrent.RateLimiter;
import org.eclipse.egit.github.core.*;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.PageIterator;
import org.eclipse.egit.github.core.service.CommitService;
import org.eclipse.egit.github.core.service.ContentsService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.eclipse.egit.github.core.service.UserService;
import utils.JsonWriter;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

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
     * The GitHub client object.
     */
    private GitHubClient client;
    private String lastPushedDate;
    private int maxStars = Integer.MAX_VALUE;
    private int starDecreaseAmount;
    private int matchingRepos = 0;
    private int checkedRepos = 0;
    private int counterSearchRequests = 0;
    private int counterRepositoryRequests = 0;
    private int counterContentRequests = 0;
    private int counterCommitRequests = 0;
    private boolean foundRepoInLastQuery;
    private boolean notFirstQuery = false;
    private RepositoryService repositoryService;
    private CommitService commitService;
    private ContentsService contentsService;
    // Request throttling using the com.google.guava 28.0-jre library
    // SEE: https://www.javadoc.io/doc/com.google.guava/guava/28.0-jre/com/google/common/util/concurrent/RateLimiter.html
    private RateLimiter requestRateLimiter;
    private RateLimiter searchRequestRateLimiter;
    private long startTime;
    private String systemStartTime;
    private Calendar calendar;
    private SimpleDateFormat formatter;

    /**
     * Crawlers Constructor.
     * @param language The programming language filter.
     * @param buildSystem The build system filter.
     * @param oAuthToken The Github OAuth token for authentication.
     */
    public GitHubCrawler(String language, String lastPushedDate, String starsDecreaseAmount , BuildSystem buildSystem, String oAuthToken){
        this.calendar = Calendar.getInstance();
        this.formatter =  new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        this.systemStartTime = formatter.format(calendar.getTime());
        this.startTime = System.nanoTime();

        this.searchLanguage = language;
        this.lastPushedDate = lastPushedDate;
        this.buildSystem = buildSystem;
        this.client = authenticate(oAuthToken);
        initGitHubServices();
        printSetup();
        calcRequestLimits();

        try {
            this.starDecreaseAmount = Integer.parseInt(starsDecreaseAmount);
            if(starDecreaseAmount <= 0){
                System.err.println("starsDecreaseAmount must be greater 0. Config file not properly set up.\nShutting down.");
                System.exit(1);
            }
        } catch (NumberFormatException e) {
            System.err.println("starsDecreaseAmount is not an integer. Config file not properly set up.\nShutting down.");
            System.exit(1);
        }
    }

    private void printSetup() {
        System.out.println("----------CONFIGURATION----------");

        System.out.println("BuildSystem: " + Config.BUILDSYSTEM);
        if(BuildSystem.CUSTOM == buildSystem)
            System.out.println("Searching for custom file: " + Config.CUSTOMFILE);
        System.out.println("Repository language: " + Config.LANGUAGE);
        if(Config.FILEPATH.isEmpty())
            System.out.println("Output is written to: " + System.getProperty("user.dir"));
        else
            System.out.println("Output is written to: " + System.getProperty("user.dir") + "/" + Config.FILEPATH);
        System.out.println("---------------------------------");
    }

    private void initGitHubServices() {
        repositoryService = new RepositoryService(client);
        commitService = new CommitService(client);
        contentsService = new ContentsService(client);
    }

    private void calcRequestLimits() {
        //Request limit values are defined here : https://developer.github.com/v3/#rate-limiting
        //Search Request limit values are defined here: https://developer.github.com/v3/search/#rate-limit
        if(Config.OAUTHTOKEN.equals("")) {
            requestRateLimiter = RateLimiter.create(60d/3600d);
            searchRequestRateLimiter = RateLimiter.create(10d/60d);
        } else { // assuming correct token was provided!
            UserService userService = new UserService(client);
            try {
                if (userService.getUser() != null) { //Check if current user is correctly authenticated.
                    System.out.println("Your current remaining request limit is: " + client.getRemainingRequests());
                    requestRateLimiter = RateLimiter.create((double) client.getRemainingRequests() / 3600d);
                }

            } catch (IOException e) {
                System.err.println(e.getMessage());
                System.exit(1);
            }
            //Not possible to request the remaining amount of search requests, thus it is set to the maximum
            // for authenticated users.
            searchRequestRateLimiter = RateLimiter.create(30d/60d);
        }

        System.out.println("Requests are throttled to " + requestRateLimiter.getRate() + " requests per second.");
        System.out.println("Search requests are throttled to " + searchRequestRateLimiter.getRate() + " requests per second.");
        System.out.println("---------------------------------");

    }


    /**
     * The main entry point to start the crawler.
     */
    public void run() {
        while(true) {
            filterRepositories(buildSearchQuery());
        }
    }

    /**
     * Function to authenticate to the GitHub client. Authenticated user have 5000 request per hour.
     * Not authenticated users have 60 search requests per hour.
     *
     * @param oAuthToken The Github OAuth token for authentication.
     * @return Either an authenticated ot not authenticated GithubClient.
     */
    private GitHubClient authenticate(String oAuthToken) {
        GitHubClient client = new GitHubClient();
        try {
            client.setOAuth2Token(oAuthToken);
        } catch (Exception e) {
            System.err.println("Authentication failed!");
            System.err.println(e.getMessage());
        }
        return client;
    }

    /**
     * Function builds the search query dependent on the outcome of the preceding 1000 repositories.
     * Initial search query is unbound on the maximum number of stars.
     * Each following query is bound by the amount of stars of the last repository (lowest stars count).
     * This function also terminates the whole crawling process when the amount of stars reaches less or equal 0.
     * @return A Map of <String,String> search qualifiers.
     */
    private Map<String, String> buildSearchQuery() {
        Map<String, String> searchQuery = new HashMap<String, String>();
        searchQuery.put("language", searchLanguage); //Search for repos with given searchlLanguage set in the config file
        searchQuery.put("is", "public"); //Search for repos that are public
        searchQuery.put("pushed", ">=" + lastPushedDate); // The pushed qualifier will return a list of repositories, sorted by the most recent commit made on any branch in the repository.
        searchQuery.put("sort", "stars");

        if(maxStars != Integer.MAX_VALUE && maxStars > 0 && foundRepoInLastQuery) {
            maxStars = maxStars - 1; // NOTE: Only the repositories that had exactly maxStars from the last query and were behind the 1000 results are omitted
            System.out.println("Querying repositories with maximum number of stars of '" + maxStars + "' from last repository of previous query.");
            searchQuery.put("stars", "<=" + (maxStars));
        } else if(!foundRepoInLastQuery && maxStars != Integer.MAX_VALUE) {
            System.out.println("No repository was found within the last 1000 crawled repositories.\nDecreasing the current stars count of "+maxStars+" by "+starDecreaseAmount+".");
            maxStars = maxStars - starDecreaseAmount;
            searchQuery.put("stars", "<=" + maxStars);
        } else if(!foundRepoInLastQuery && maxStars == Integer.MAX_VALUE && notFirstQuery) {
            //NOTE: This case is ignored. If we do not find any popular repository within the first 1000 repositories,
            //then it would be unnecessary to crawl any further for it.
            System.err.println("No popular repository was found within the first query without a stars limit.\nShutting Down.");
            System.err.println("NOTE: This case is ignored. If we do not find any popular repository within the first 1000 repositories, " +
                    "then it would be unnecessary to crawl any further for it.");
            System.exit(1);
        }
        notFirstQuery = true;
        foundRepoInLastQuery = false;
        if(maxStars <= 0) {
            //including 0 otherwise there is no other termination, due to the case that when the stars count reaches 0 and the query finds repositories,
            // it will set the stars count again to 0, resulting to the same query in a loop.
            System.out.println("Minimum value for stars reached. Crawling Finished\n");
            System.out.println("----------PRINTING STATS----------");
            long endTime   = System.nanoTime();
            long duration = endTime - startTime;
            System.out.println("Crawler started at: " + systemStartTime);
            System.out.println("Crawler terminated at: " + formatter.format(calendar.getTime()));
            System.out.println("Overall execution time in seconds: " + TimeUnit.NANOSECONDS.toSeconds(duration));
            System.out.println("Overall execution time in minutes: " + (double)TimeUnit.NANOSECONDS.toSeconds(duration)/60);
            System.out.println("Overall execution time in hours: " + (double)TimeUnit.NANOSECONDS.toSeconds(duration)/3600 + "\n");

            System.out.println("Total amount of crawled repositories: " + checkedRepos);
            System.out.println("Total amount of matching repositories: " + matchingRepos + "\n");

            System.out.println("Amount of sent search requests: " + counterSearchRequests);
            System.out.println("Amount of sent repository requests: " + counterRepositoryRequests);
            System.out.println("Amount of sent content requests: " + counterContentRequests);
            System.out.println("Amount of sent commit requests: " + counterCommitRequests);
            System.out.println("Total amount of sent requests: " + (counterSearchRequests + counterRepositoryRequests + counterContentRequests + counterCommitRequests));

            System.out.println("----------------------------------");

            System.out.println("Shutting down");
            System.exit(0);
        }
        return searchQuery;
    }

    /**
     * Function that sends the search request.
     * @param searchQuery The search query qualifiers.
     * @param page The 0-10 pages to query.
     * @return A List of SearchRepository objects containing metadata.
     */
    private List<SearchRepository> queryRepositories(Map<String, String> searchQuery, int page){
        try {
            //search requests also count as a general request and thus are also throttled
            //by the general request limiter.
            requestRateLimiter.acquire();
            searchRequestRateLimiter.acquire();
            counterSearchRequests++;
            return repositoryService.searchRepositories(searchQuery, page);
        } catch (IOException e) {
            System.err.println("Something went wrong while performing the repository search request.\nAborting.\n");
            System.err.println(e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * Sends a query to get the repository model by its owner and repository name.
     * @param searchRepository The repository to query for.
     * @return The repository model.
     */
    private Repository queryRepoByOwnerAndName(SearchRepository searchRepository) {
        try {
            requestRateLimiter.acquire();
            counterRepositoryRequests++;
            return repositoryService.getRepository(searchRepository.getOwner(), searchRepository.getName());
        } catch(IOException e) {
            System.err.println("Something went wrong while getting the Repository by Owner and repository Name. Skipping to next repository.");
            System.err.println(e.getMessage());
        }
        return null;
    }

    /**
     * Function that searches for repository matches by iterating over the array response from the search query.
     * I.e. by checking its root contents for the specified files within the config.properties file.
     * If a matching is found all required metadata is collected and stored into the repositories.json file.
     * @param searchQuery The search query to send.
     */
    private void filterRepositories(Map<String, String> searchQuery) {

        for (int page = 1; page <= 10; page++) {

            List<SearchRepository> searchRepositoryResponse = queryRepositories(searchQuery, page);

            if (searchRepositoryResponse.isEmpty()) { // If we reached a page number that returns no repositories (empty list) in the query.
                System.out.println("Found " + searchRepositoryResponse.size() + " Repos by search at page " + page);
                System.out.println("Crawling Finished.\nShutting down.");
                System.exit(0);
                break;
            } else {

                System.out.println("Query Response:\nNumber Repos: " + searchRepositoryResponse.size() + "\nOn page " + page + ".\n");

                for (SearchRepository searchRepository : searchRepositoryResponse) {
                    //Get the repository model.
                    Repository repositoryOfOwnerAndName = queryRepoByOwnerAndName(searchRepository);
                    if (repositoryOfOwnerAndName != null) {
                        maxStars = repositoryOfOwnerAndName.getWatchers();
                        System.out.println("-----------------------------------------------");
                        System.out.println("Current maximum stars count: " + maxStars);
                        checkedRepos++;
                        //Detect BuildSystem subroutine
                        BuildSystem foundBuildSystem = getFileContentsAtRootDir(repositoryOfOwnerAndName);
                        if (foundBuildSystem == buildSystem) { //BuildSystem was detected. Create a new RMetaData object and store all information
                            matchingRepos++;
                            foundRepoInLastQuery = true;
                            System.err.println("Overall detected repos: " + matchingRepos);
                            RMetaData metaDataObject = createRMetaDataObject(repositoryOfOwnerAndName, foundBuildSystem);
                            JsonWriter.getInstance().writeRepositoryToJson(metaDataObject);
                        }
                        System.out.println("Remaining Request: " + client.getRemainingRequests());
                    }
                }
            }
        }
        System.out.println("Maximum number of 1000 repositories were processed within one search query.\nSkipping others due to limitation.");
    }

    /**
     * Constructs the RMetaData object for later serialization into json and storage in to the repositories.json file.
     * @param repository The Repository model
     * @param buildSystem The BuildSystem of the repository.
     * @return The RMetaData object.
     */
    private RMetaData createRMetaDataObject(Repository repository, BuildSystem buildSystem) {
        RMetaData meteDataObject = new RMetaData(); //TODO: put this function into the model?
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
                                                                     // The matchingRepos now only increases if a project is starred.
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
     * Gets the latest commit id (sha) from the repository default (master) branch.
     *
     * @param repository The repository we are currently looking at.
     * @return The latest commit id as a String.
     */
    private String getLatestCommitId(Repository repository){
        requestRateLimiter.acquire();
        counterCommitRequests++;
        PageIterator<RepositoryCommit> repositoryCommitList = commitService.pageCommits(repository, 1);
        if(repositoryCommitList.hasNext())
            return repositoryCommitList.next().iterator().next().getSha();
        else return "";
    }

    /**
     * Detects if the repository contains specific build files required by the currently searched build system.
     *
     * @param repository The repository to detect the build system from
     * @return The detected BuildSystem
     */
    private BuildSystem getFileContentsAtRootDir(Repository repository) {
        BuildSystem detectedBuildSystem = BuildSystem.UNKNOWN;
        List<String> filePaths = new ArrayList<>();

        try {
            requestRateLimiter.acquire();
            List<RepositoryContents> repositoryContents = contentsService.getContents(repository);
           // contentsService.getContents(repository, "path/to/folder"); //TODO: use this function to search for files on specific path!
            counterContentRequests++;
            switch (buildSystem) {
                case CMAKE:
                    if(repositoryContents.stream().anyMatch(o -> o.getName().equals(BuildSystem.CMAKE.getBuildFiles()[0]))) {
                        // filePaths.add(""); // set here the filepath to the given file.
                        //check github library docu for requesting all files within a given path!
                        detectedBuildSystem = BuildSystem.CMAKE;
                    }
                    break;
                case AUTOTOOLS://((configure.ac || configure.in) && Makefile.am))
                    if(((repositoryContents.stream().anyMatch(o -> o.getName().equals(BuildSystem.AUTOTOOLS.getBuildFiles()[0])) ||
                        repositoryContents.stream().anyMatch(o -> o.getName().equals(BuildSystem.AUTOTOOLS.getBuildFiles()[1]))) &&
                        repositoryContents.stream().anyMatch(o -> o.getName().equals(BuildSystem.AUTOTOOLS.getBuildFiles()[2])))) {
                        detectedBuildSystem = BuildSystem.AUTOTOOLS;
                    }
                    break;
                case MAKE:
                    if(repositoryContents.stream().anyMatch(o -> o.getName().equals(BuildSystem.MAKE.getBuildFiles()[0]))) {
                        detectedBuildSystem = BuildSystem.MAKE;
                    }
                    break;
                case CUSTOM:
                    if(repositoryContents.stream().anyMatch(o -> o.getName().equals(Config.CUSTOMFILE))) {
                        detectedBuildSystem = BuildSystem.CUSTOM;
                    }
                    break;
                default:
                    System.out.println("Running default");
                    detectedBuildSystem = BuildSystem.UNKNOWN;
                    break;
            }
        } catch (IOException e) {
            System.err.println("Something went wrong while querying the repository contents.\n");
            System.err.println(e.getMessage());
        }
        detectedBuildSystem.setFilePaths(filePaths);
        return detectedBuildSystem;
    }
}
