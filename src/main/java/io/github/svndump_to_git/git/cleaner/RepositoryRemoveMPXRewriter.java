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
import java.util.List;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import io.github.svndump_to_git.git.model.GitRepositoryUtils;
import io.github.svndump_to_git.git.model.ref.utils.GitRefUtils;
import io.github.svndump_to_git.git.model.tree.GitTreeData;
import io.github.svndump_to_git.git.model.tree.utils.GitTreeProcessor;
import io.github.svndump_to_git.git.utils.ExternalGitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingest a series of file paths and or globbing patterns like *.mpx and then for each git commit under consideration
 * rewrite the commit by removing the files that match the pattern's given.
 * 
 *  Use the same rewrite technique as the RepositoryBlobRewriter where the matched blob's content is deleted but it still exists in the rewritten tree.  its just that the content says its been deleted as
 *  part of the svn to git conversion.
 *  
 * @author ocleirig
 * 
 */
public class RepositoryRemoveMPXRewriter extends AbstractRepositoryCleaner {

	private static final Logger log = LoggerFactory
			.getLogger(RepositoryRemoveMPXRewriter.class);
	private ObjectId replacementContentBlobId;


	/**
	 * 
	 */
	public RepositoryRemoveMPXRewriter() {
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * RepositoryCleaner#validateArgs(java.lang
	 * .String[])
	 */
	@Override
	public void validateArgs(List<String> args) throws Exception {

		if (args.size() != 2 && args.size() != 4) {
			log.error("USAGE: <source git repository meta directory> <blob content replacement>  [<branchRefSpec> <git command path>]");
			log.error("\t<git repo meta directory> : the path to the meta directory of the source git repository");
			log.error("\t<blob content replacement> : content to replace the matched blob with");
			log.error("\t<branchRefSpec> : git refspec from which to source the graph to be rewritten");
			log.error("\t<git command path> : the path to a native git ");
			throw new IllegalArgumentException("invalid arguments");
		}

		setRepo(GitRepositoryUtils.buildFileRepository(
				new File(args.get(0)).getAbsoluteFile(), false));

		String blobReplacmentContent = args.get(1);
		
		replacementContentBlobId = getRepo().newObjectInserter().insert(Constants.OBJ_BLOB, blobReplacmentContent.getBytes());
		
		if (args.size() >= 3)
			setBranchRefSpec(args.get(2).trim());

		if (args.size() == 4)
			setExternalGitCommandPath(args.get(3).trim());
	}

	
	
	/* (non-Javadoc)
	 * @see AbstractRepositoryCleaner#processCommitTree(org.eclipse.jgit.revwalk.RevCommit, io.github.svndump_to_git.git.model.tree.GitTreeData)
	 */
	@Override
	protected boolean processCommitTree(RevCommit commit, GitTreeData tree)
			throws MissingObjectException, IncorrectObjectTypeException,
			CorruptObjectException, IOException {
		
		boolean recreateCommit = false;
		
		TreeWalk tw = new TreeWalk(getRepo());
		
		tw.setRecursive(true);
		
		tw.addTree(commit.getTree().getId());
		
		while (tw.next()) {
			
			if (!tw.getFileMode(0).equals(FileMode.REGULAR_FILE))
				continue;
			
			String pathName = tw.getPathString();
			
			if (pathName.toLowerCase().endsWith(".mpx")) {
				
				tree.addBlob(pathName, replacementContentBlobId);
				
				recreateCommit = true;
			}
				
			
		}
		
		tw.close();
		
		return recreateCommit;
	}

	
	/* (non-Javadoc)
	 * @see AbstractRepositoryCleaner#getFileNameSuffix()
	 */
	@Override
	protected String getFileNameSuffix() {
		return "remove-mpx-files";
	}

	
}
