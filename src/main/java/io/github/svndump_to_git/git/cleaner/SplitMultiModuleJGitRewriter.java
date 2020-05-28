/**
 * 
 */
package io.github.svndump_to_git.git.cleaner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import io.github.svndump_to_git.cleaner.model.GitSvnIdUtils;
import io.github.svndump_to_git.git.model.GitRepositoryUtils;
import io.github.svndump_to_git.git.model.ref.utils.GitRefUtils;
import io.github.svndump_to_git.git.model.tree.GitTreeData;
import io.github.svndump_to_git.git.model.tree.utils.GitTreeProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use JGit directly to rewrite multi module repos
 *
 */
public class SplitMultiModuleJGitRewriter {

	private static final Logger log = LoggerFactory.getLogger(SplitMultiModuleJGitRewriter.class);
	
	private Repository repo;

	private GitTreeProcessor treeProcessor;

	private ObjectInserter objectInserter;

	private Map <ObjectId, ObjectId> originalCommitIdToNewCommitIdMap;

	private String targetPath;

	private final String[] primaryBranchNames;
	private final boolean filterBranchTips;

	public SplitMultiModuleJGitRewriter(String gitRepositoryPath, boolean bare, String targetPath, String[] primaryBranchNames, boolean filterBranchTips) throws IOException {
		
		this.targetPath = targetPath;
		this.primaryBranchNames = primaryBranchNames;
		this.filterBranchTips = filterBranchTips;
		File gitRepository = new File(gitRepositoryPath);


		repo = GitRepositoryUtils
				.buildFileRepository(gitRepository, false, bare);
		
		treeProcessor = new GitTreeProcessor(repo);

		objectInserter = repo.newObjectInserter();
		
		originalCommitIdToNewCommitIdMap = new LinkedHashMap<>();
		
	}
	
	public void execute() throws IOException {
		
		Map<String, Ref> branchHeads = repo.getRefDatabase().getRefs(Constants.R_HEADS);

		Map<String, Ref> primaryBranchHeads = new HashMap<>();

		for (String primaryBranchName: primaryBranchNames) {
			Ref primaryBranchHead = branchHeads.remove(primaryBranchName);
			primaryBranchHeads.put(primaryBranchName, primaryBranchHead);
		}
		
		HashMap<ObjectId, Set<Ref>> commitToBranchMap = new HashMap<ObjectId, Set<Ref>>();

		ObjectReader or = repo.newObjectReader();
		
		RevWalk branchWalk = new RevWalk(or);
		
		for (Ref branchRef : branchHeads.values()) {

			ObjectId branchObjectId = branchRef.getObjectId();

			Set<Ref> refs = commitToBranchMap.get(branchObjectId);

			if (refs == null) {
				refs = new HashSet<>();
				commitToBranchMap.put(branchObjectId, refs);
			}

			refs.add(branchRef);

		}
		
		// rewrite the trunk
		for (String primaryBranchName: primaryBranchNames) {

			ObjectId newPrimaryBranchCommitId = processPrimaryBranch(or, primaryBranchHeads.get(primaryBranchName).getObjectId());

			GitRefUtils.createOrUpdateBranch(repo, Constants.R_HEADS + primaryBranchName, newPrimaryBranchCommitId);
		}
		for (Ref branchRef : branchHeads.values()) {
			
			ObjectId branchHeadId = branchRef.getObjectId();

			ObjectId newCommitId = this.originalCommitIdToNewCommitIdMap.get(branchHeadId);

			if (newCommitId != null)
				GitRefUtils.createOrUpdateBranch(repo, branchRef.getName(), newCommitId);
			
		}
		
		
		or.close();
	}
	

	private ObjectId processPrimaryBranch(ObjectReader or, AnyObjectId trunkHeadId) throws MissingObjectException, IncorrectObjectTypeException, IOException {
		
		RevWalk walkRepo = new RevWalk(or);

		walkRepo.setRevFilter(RevFilter.ALL);

		walkRepo.markStart(walkRepo.parseCommit(trunkHeadId));
		
		// sort parents before children
		walkRepo.sort(RevSort.TOPO, true);
		walkRepo.sort(RevSort.REVERSE, true);
		
		// only return commits containing the target path that has a non-zero diff to its parent
		// JGit will automatically simplify history so that the commits returned skip over commits that don't match this criteria.
//		walkRepo.setTreeFilter(AndTreeFilter.create(PathFilter.create(targetPath), TreeFilter.ALL));
		
		Iterator<RevCommit> iter = walkRepo.iterator();
		
		ObjectId lastCommitId = null;
		
		while (iter.hasNext()) {
			RevCommit commit = (RevCommit) iter.next();

			ObjectId newCommit = originalCommitIdToNewCommitIdMap.get(commit.getId());

			if (newCommit != null) {
				// may have rewritten this commit for another primary branch.
				continue;
			}
			
			GitTreeData tree = treeProcessor
					.extractExistingTreeDataFromCommit(commit.getId());

			// check if the target path exists in the commit

			// check for a diff on the target path if not a merge commit.

			/*
			 * Process in reverse order from old to new.
			 */
			CommitBuilder builder = new CommitBuilder();
			
			builder.setAuthor(commit.getAuthorIdent());
			builder.setCommitter(commit.getCommitterIdent());

			builder.setMessage(GitSvnIdUtils
					.applyPathToExistingGitSvnId(commit.getFullMessage(), targetPath));
			
			builder.setEncoding(commit.getEncoding());

			ObjectId targetTreeId = tree.find(repo, targetPath);

			if (targetTreeId == null) {
				originalCommitIdToNewCommitIdMap.put(commit.getId(), lastCommitId);
				continue; // commit does not contain the target path so skip over it.
			}

			List<ObjectId> convertedParents = convertParents(commit);

			if (commit.getParentCount() == 1) {
				// check there is a diff on the target path

				TreeWalk treewalk = new TreeWalk(repo);

				treewalk.addTree(commit.getParent(0).getTree());
				treewalk.addTree(commit.getTree());

				List<DiffEntry> differences = DiffEntry.scan(treewalk);

				boolean foundTargetPathChange = false;

				for (DiffEntry de : differences) {
					if (de.getNewPath().startsWith(targetPath)) {
						foundTargetPathChange = true;
						break;
					}
				}

				if (!foundTargetPathChange) {
					originalCommitIdToNewCommitIdMap.put(commit.getId(), lastCommitId);
					continue; // skip over this commit since it is a no-op.
				}

			}
			else if (commit.getParentCount() > 1) {

				if (commit.getParentCount() != convertedParents.size()) {
					// skip over
					originalCommitIdToNewCommitIdMap.put(commit.getId(), lastCommitId);
					continue;
				}
			}

			builder.setTreeId(targetTreeId);
			
			builder.setParentIds(convertedParents);
			
			lastCommitId = objectInserter.insert(builder);
			
			originalCommitIdToNewCommitIdMap.put(commit.getId(), lastCommitId);
			
		}
		
		walkRepo.close();
		
		return lastCommitId;
		
		
		
		
		
	}

	

	private List<ObjectId> convertParents(RevCommit commit) {
		
		Set<ObjectId> convertedParentIds = new LinkedHashSet<>();
		
		for (RevCommit parentCommit : commit.getParents()) {
			
			ObjectId convertedParentId = this.originalCommitIdToNewCommitIdMap.get(parentCommit.getId());
			
			if (convertedParentId == null) {
				convertedParentIds.add(parentCommit.getId());
			}
			else {
				convertedParentIds.add(convertedParentId);
			}
			
		}
		
		return new ArrayList<>(convertedParentIds);
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		if (args.length != 5) {
			log.error("USAGE: <git repository> <bare:true or false> <target path> <primary branch name> <filter branch tips:true or false>");
			System.exit(1);
		}
		
		String gitRepositoryPath = args[0];
		
		boolean bare = Boolean.valueOf(args[1].trim());
		
		String targetPath = args[2];

		String[] primaryBranchNames = args[3].trim().split(",");

		/*
		 If true then we filter the tip of the branches aswell as the primary branch.  jenkins ci conversion had this value as false where it wouldn't
		 filter the tags since they were already made on a per module basis.  Filtering the branch tips to true is more like what you would expect from a
		 git filter-branch --subdirectory-filter <sub-dir> -- --all command.
		 */
		boolean filterBranchTips = "true".equals(args[4])?true:false;
		
		try {
			SplitMultiModuleJGitRewriter splitter = new SplitMultiModuleJGitRewriter (gitRepositoryPath, bare, targetPath, primaryBranchNames, filterBranchTips);
			
			splitter.execute();
			
		} catch (Exception e) {
			log.error("unexpected fatal exception = ", e);
		}

	}

}
