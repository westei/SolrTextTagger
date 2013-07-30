/*
 This software was produced for the U. S. Government
 under Contract No. W15P7T-11-C-F600, and is
 subject to the Rights in Noncommercial Computer Software
 and Noncommercial Computer Software Documentation
 Clause 252.227-7014 (JUN 1995)

 Copyright 2013 The MITRE Corporation. All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.opensextant.solrtexttagger;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;

/**
 * Tags maximum string of words in a corpus.  This is a callback-style API
 * in which you implement {@link #tagCallback(int, int, Object)}.
 *
 * This class should be independently usable outside Solr.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public abstract class Tagger {

  private final TokenStream tokenStream;
  //private final CharTermAttribute termAtt;
  private final PositionIncrementAttribute posIncAtt;
  private final TermToBytesRefAttribute byteRefAtt;
  private final OffsetAttribute offsetAtt;
  private final TaggingAttribute lookupAtt;

  private final TagClusterReducer tagClusterReducer;
  private final Terms terms;
  private final Bits liveDocs;

  public Tagger(Terms terms, Bits liveDocs, TokenStream tokenStream,
                TagClusterReducer tagClusterReducer) throws IOException {
    this.terms = terms;
    this.liveDocs = liveDocs;
    this.tokenStream = tokenStream;
    //termAtt = tokenStream.addAttribute(CharTermAttribute.class);
    byteRefAtt = tokenStream.addAttribute(TermToBytesRefAttribute.class);
    posIncAtt = tokenStream.addAttribute(PositionIncrementAttribute.class);
    offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
    lookupAtt = tokenStream.addAttribute(TaggingAttribute.class);
    tokenStream.reset();

    this.tagClusterReducer = tagClusterReducer;
  }

  public void process() throws IOException {
    if (terms == null)
      return;

    //a shared pointer to the head used by this method and each Tag instance.
    final TagLL[] head = new TagLL[1];

    TermPrefixCursor cursor = null;//re-used
    TermsEnum termsEnum = null;//re-used

    int lastStartOffset = -1;

    while (tokenStream.incrementToken()) {

      //sanity-check that start offsets don't decrease
      if (lastStartOffset > offsetAtt.startOffset())
        throw new IllegalStateException("startOffset must be >= the one before: "+lastStartOffset);
      lastStartOffset = offsetAtt.startOffset();

      //-- If PositionIncrement > 1 then finish all tags
      int posInc = posIncAtt.getPositionIncrement();
      if (posInc < 1) {
        throw new IllegalStateException("term: " + byteRefAtt.getBytesRef().utf8ToString()
            + " analyzed to a token with posinc < 1: "+posInc);
      } else if (posInc > 1) {
        advanceTagsAndProcessClusterIfDone(head, null);
      }

      final BytesRef term;
      //NOTE: we need to lookup tokens if
      // * the LookupAtt is true OR
      // * there are still advancing tags (to find the longest possible match)
      if(lookupAtt.isTaggable() || head[0] != null){
        //-- Lookup the term id from the next token
        byteRefAtt.fillBytesRef();
        term = byteRefAtt.getBytesRef();
        if (term.length == 0) {
          throw new IllegalArgumentException("term: " + term.utf8ToString() + " analyzed to a zero-length token");
        }
      } else { //no current cluster AND lookup == false ...
        term = null; //skip this token
      }

      //-- Process tag
      advanceTagsAndProcessClusterIfDone(head, term);

      //-- only create new Tags for Tokens we need to lookup
      if (lookupAtt.isTaggable() && term != null) {

        //determine if the the terms index has a term starting with the provided term
        // TODO cache hashcodes of valid first terms (directly from char[]?) to skip lookups?
        termsEnum = terms.iterator(termsEnum);
        if (cursor == null)//re-usable
          cursor = new TermPrefixCursor();
        if (cursor.advanceFirst(term, termsEnum)) {
          TagLL newTail = new TagLL(head, cursor, offsetAtt.startOffset(), offsetAtt.endOffset(), null);
          termsEnum = null;//because the cursor now "owns" this instance
          cursor = null;//because the new tag now "owns" this instance
          //and add it to the end
          if (head[0] == null) {
            head[0] = newTail;
          } else {
            for (TagLL t = head[0]; true; t = t.nextTag) {
              if (t.nextTag == null) {
                t.addAfterLL(newTail);
                break;
              }
            }
          }
        }
      }//if termId >= 0
    }//end while(incrementToken())

    //-- Finish all tags
    advanceTagsAndProcessClusterIfDone(head, null);
    assert head[0] == null;

    tokenStream.end();
    tokenStream.close();
  }

  private void advanceTagsAndProcessClusterIfDone(TagLL[] head, BytesRef term) throws IOException {
    //-- Advance tags
    final int endOffset = term != null ? offsetAtt.endOffset() : -1;
    boolean anyAdvance = false;
    for (TagLL t = head[0]; t != null; t = t.nextTag) {
      anyAdvance |= t.advance(term, endOffset);
    }

    //-- Process cluster if done
    if (!anyAdvance && head[0] != null) {
      tagClusterReducer.reduce(head);
      for (TagLL t = head[0]; t != null; t = t.nextTag) {
        assert t.value != null;
        tagCallback(t.startOffset, t.endOffset, t.value);
      }
      head[0] = null;
    }
  }

  /**
   * Invoked by {@link #process()} for each tag found.  endOffset is always >= the endOffset given in the previous
   * call.
   * @param startOffset The character offset of the original stream where the tag starts.
   * @param endOffset One more than the character offset of the original stream where the tag ends.
   * @param docIdsKey A reference to the matching docIds that can be resolved via {@link #lookupDocIds(Object)}.
   */
  protected abstract void tagCallback(int startOffset, int endOffset, Object docIdsKey);

  /**
   * Returns a sorted array of integer docIds given the corresponding key.
   * @param docIdsKey The lookup key.
   * @return Not null
   */
  protected DocsEnum lookupDocIds(Object docIdsKey) {
    return (DocsEnum) docIdsKey;
  }

  class TermPrefixCursor {

    static final byte SEPARATOR_CHAR = ' ';
    BytesRef prefixBuf;
    TermsEnum termsEnum;
    DocsEnum docsEnum;

    boolean advanceFirst(BytesRef word, TermsEnum termsEnum) throws IOException {
      this.termsEnum = termsEnum;
      prefixBuf = word;//don't copy it unless we have to
      if (seekPrefix()) {//... and we have to
        prefixBuf = new BytesRef(64);
        prefixBuf.copyBytes(word);
        return true;
      } else {
        prefixBuf = null;//just to be darned sure 'word' isn't referenced here
        return false;
      }
    }

    boolean advanceNext(BytesRef word) throws IOException {
      //append to existing
      prefixBuf.grow(1 + word.length);
      prefixBuf.bytes[prefixBuf.length++] = SEPARATOR_CHAR;
      prefixBuf.append(word);
      return seekPrefix();
    }

    /** Seeks to prefixBuf or the next prefix of it. Sets docsEnum. **/
    private boolean seekPrefix() throws IOException {
      TermsEnum.SeekStatus seekStatus = termsEnum.seekCeil(prefixBuf);

      docsEnum = null;//can't re-use :-(
      switch (seekStatus) {
        case END:
          return false;

        case FOUND:
          docsEnum = termsEnum.docs(liveDocs, docsEnum, DocsEnum.FLAG_NONE);
          if (liveDocs == null)//then docsEnum is guaranteed to match docs
            return true;

          //need to verify there are indeed docs, which might not be so when there is a filter
          if (docsEnum.nextDoc() != DocsEnum.NO_MORE_DOCS) {
            docsEnum = termsEnum.docs(liveDocs, docsEnum, DocsEnum.FLAG_NONE);//reset
            return true;
          }
          //Pretend we didn't find it; go to next term
          docsEnum = null;
          if (termsEnum.next() == null) { // case END
            return false;
          }
          //fall through to NOT_FOUND

        case NOT_FOUND:
          //termsEnum must start with prefixBuf to continue
          BytesRef teTerm = termsEnum.term();

          if (teTerm.length > prefixBuf.length) {
            for (int i = 0; i < prefixBuf.length; i++) {
              if (prefixBuf.bytes[prefixBuf.offset + i] != teTerm.bytes[teTerm.offset + i])
                return false;
            }
            if (teTerm.bytes[teTerm.offset + prefixBuf.length] != SEPARATOR_CHAR)
              return false;
            return true;
          }
          return false;
      }
      throw new IllegalStateException(seekStatus.toString());
    }

    /** should only be called after advance* returns true */
    DocsEnum getDocsEnum() {
      return docsEnum;
    }
  }
}
