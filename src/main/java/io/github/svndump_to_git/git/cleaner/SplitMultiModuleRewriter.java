/*
 *  Copyright 2014 The Kuali Foundation Licensed under the
 *	Educational Community License, Version 2.0 (the "License"); you may
 *	not use this file except in compliance with the License. You may
 *	obtain a copy of the License at
 *
 *	http://www.osedu.org/licenses/ECL-2.0
 *
 *	Unless required by applicable law or agreed to in writing,
 *	software distributed under the License is distributed on an "AS IS"
 *	BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 *	or implied. See the License for the specific language governing
 *	permissions and limitations under the License.
 */
package io.github.svndump_to_git.git.cleaner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.github.svndump_to_git.cleaner.model.GitSvnIdUtils;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import io.github.svndump_to_git.git.cleaner.model.SkipOverCommitException;
import io.github.svndump_to_git.git.model.GitRepositoryUtils;
import io.github.svndump_to_git.git.model.tree.GitTreeData;

/**
 * In the case where a trunk contained many modules and we want to extract just
 * the commits related to a specific module.
 *
 * But we also want the release branches unchanged (but rewritten to update to
 * the rewritten trunk commits.
 *
 * this also fixes up the git-svn-id comment for the path that is collapsed.
 * 
 * @author ocleirig
 * 
 */
public class SplitMultiModuleRewriter extends AbstractRepositoryCleaner {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SplitMultiModuleRewriter.class);

	private Map<ObjectId, Set<ObjectId>> skippedCommitIdToParentsCommitIds = new LinkedHashMap<ObjectId, Set<ObjectId>>();

	private String targetPath;

	private ObjectId trunkHeadObjectId;

	private RevWalk finder;

	/**
	 *
	 */
	public SplitMultiModuleRewriter() {
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * RepositoryCleaner#validateArgs(java.lang
	 * .String[])
	 */
	@Override
	public void validateArgs(java.util.List<String> args) throws Exception {

		if (args.size() != 2 && args.size() != 3) {
			log.error("USAGE: <source git repository meta directory> <path to collapse> [<git command path>]");
			log.error("\t<git repo meta directory> : the path to the meta directory of the source git repository");
			log.error("\t<path to collapse> : the path to a native git ");
			log.error("\t<git command path> : the path to a native git ");
			throw new IllegalArgumentException("invalid arguments");
		}

		setRepo(GitRepositoryUtils.buildFileRepository(new File(args.get(0)).getAbsoluteFile(), false));

		setBranchRefSpec(Constants.R_HEADS);

		if (args.size() == 3)
			setExternalGitCommandPath(args.get(2).trim());

		targetPath = args.get(1).trim();

		trunkHeadObjectId = getRepo().resolve("trunk");
		
		finder = new RevWalk(getRepo());
	}

	@Override
	protected boolean processCommitTree(org.eclipse.jgit.revwalk.RevCommit commit,
			io.github.svndump_to_git.git.model.tree.GitTreeData tree)
			throws org.eclipse.jgit.errors.MissingObjectException, org.eclipse.jgit.errors.IncorrectObjectTypeException,
			org.eclipse.jgit.errors.CorruptObjectException, java.io.IOException, SkipOverCommitException {

		return true;

	}

	private boolean treeSameAsAllParents(ObjectId treeId, RevCommit commit)
			throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException {

		if (commit.getParentCount() == 0)
			return false;

		int treeSameCount = 0;
		for (RevCommit parent : commit.getParents()) {

			ObjectId parentTreeId = GitRepositoryUtils.findInTree(getRepo(), parent.getTree().getId(), targetPath);

			if (parentTreeId != null && treeId.equals(parentTreeId))
				treeSameCount++;
		}

		if (treeSameCount == commit.getParentCount())
			return true;
		else
			return false;

	}

	@Override
	protected CommitBuilder createCommitBuilder(RevCommit commit, GitTreeData tree)
			throws java.io.IOException, SkipOverCommitException {

		CommitBuilder builder = new CommitBuilder();

		builder.setAuthor(commit.getAuthorIdent());
		builder.setMessage(commit.getFullMessage());

		builder.setCommitter(commit.getCommitterIdent());

		boolean setTree = true;

		if (!super.commitToBranchMap.containsKey(commit.getId()) || commit.getId().equals(trunkHeadObjectId)) {

			ObjectId targetTree = tree.find(getRepo(), targetPath);

			if (targetTree != null && !treeSameAsAllParents(targetTree, commit)) {

				builder.setTreeId(targetTree);

				builder.setMessage(GitSvnIdUtils
						.applyPathToExistingGitSvnId(commit.getFullMessage(), targetPath));

				setTree = false;

			} else {

				if (targetTree == null) {
					// current commit doesn't contain the target tree
					log.info("commit(" + commit.getId() + ") doesn't contain the target tree(" + targetPath
							+ ") skipping.");

				} else {
					// current commit contains the target tree but with no
					// difference with its parents.
					log.info("commit(" + commit.getId() + ") doesn't change the target tree(" + targetPath
							+ ") skipping.");
				}

				this.skippedCommitIdToParentsCommitIds.put(commit.getId(), getParentCommitIds(commit));
				throw new SkipOverCommitException();
			}

			// else fall through
		}

		if (setTree) {

			if (tree.isTreeDirty()) {

				ObjectId newTreeId = tree.buildTree(inserter);

				builder.setTreeId(newTreeId);
			} else {
				builder.setTreeId(commit.getTree().getId());
			}
		}

		builder.setEncoding("UTF-8");

		Set<ObjectId> newParents = processParents(commit);

		builder.setParentIds(new ArrayList<>(newParents));

		return builder;
	}

	@Override
	protected void onSkipOverCommit(RevCommit commit, GitTreeData tree) {
		// check if any branches need to be moved
		if (commitToBranchMap.containsKey(commit.getId())) {

			Set<Ref> refs = commitToBranchMap.get(commit.getId());

			for (Ref branchRef : refs) {
				
				String adjustedBranchName = Constants.R_HEADS
						+ branchRef.getName().substring(getBranchRefSpec().length());
				
				deferDelete(branchRef.getName(), branchRef.getObjectId());
				
				Set<ObjectId> parents = processParents(commit);

				if (parents.size() == 0) {
					log.info("losing reference to  " + branchRef.getName() + " due to not having any parents");

				}
				else if (parents.size() > 0) {
					
					ObjectId newCommitId = parents.iterator().next();
				
					deferCreate(adjustedBranchName, newCommitId);

					onBranchRefCreate(adjustedBranchName, newCommitId);
					
					log.info("remapping " + branchRef.getName() + " to parent 1 " + newCommitId);
				} 


			}

		}
	}

	private ObjectId findValidConvertedCommit(ObjectId commit)
			throws MissingObjectException, IncorrectObjectTypeException, IOException {

		ObjectId convertedId = null;

		finder.reset();
		
		finder.setTreeFilter(PathFilter.create(targetPath));

		finder.sort(RevSort.TOPO);
		
		finder.markStart(finder.parseCommit(commit));

		RevCommit targetCommit = finder.next();

		if (targetCommit != null) {

			convertedId = super.originalCommitIdToNewCommitIdMap.get(targetCommit.getId());

		}
		
		return convertedId;

	}

	@Override
	protected Set<ObjectId> processParents(RevCommit commit) {
		
		Set<ObjectId> translatedParentCommitIds = new LinkedHashSet<>();
		
		for (RevCommit parent : commit.getParents()) {
			
			try {
				ObjectId convertedParentId = findValidConvertedCommit(parent.getId());
				
				if (convertedParentId != null)
					translatedParentCommitIds.add(convertedParentId);
				else {
					Set<ObjectId> skippedParents = this.skippedCommitIdToParentsCommitIds.get(parent.getId());
					
					if (skippedParents != null) {
						
						for (ObjectId parentId : skippedParents) {
							ObjectId convertedSkippedParentId = findValidConvertedCommit(parentId);
							
							if (convertedSkippedParentId != null)
								translatedParentCommitIds.add(convertedSkippedParentId);
						}
					}
				}
				
			} catch (MissingObjectException e) {
				throw new RuntimeException(e);
			} catch (IncorrectObjectTypeException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			
		}
		return translatedParentCommitIds;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * AbstractRepositoryCleaner#getFileNameSuffix
	 * ()
	 */
	@Override
	protected String getFileNameSuffix() {
		return "split-multi-module";
	}

	@Override
	public void close() {
		finder.close();
		super.close();
	}
	
	

}
