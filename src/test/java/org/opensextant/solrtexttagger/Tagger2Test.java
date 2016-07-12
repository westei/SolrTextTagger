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

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test the {@link org.opensextant.solrtexttagger.TaggerRequestHandler}.
 */
public class Tagger2Test extends AbstractTaggerTest {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");
  }

  @Test
  /** whole matching, no sub-tags */
  public void testLongestDominantRight() throws Exception {
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    buildNames("in", "San", "in San", "Francisco", "San Francisco",
        "San Francisco State College", "College of California",
        "Clayton", "Clayton North", "North Carolina");

    assertTags("He lived in San Francisco.",
        "in", "San Francisco");

    assertTags("He enrolled in San Francisco State College of California",
        "in", "San Francisco State College");

    assertTags("He lived in Clayton North Carolina",
        "in", "Clayton", "North Carolina");

  }

  @Test
  /** posInc > 1 https://github.com/OpenSextant/SolrTextTagger/issues/2 */
  public void testPosIncJump() throws Exception {
    this.overlaps = "LONGEST_DOMINANT_RIGHT";
    StringBuilder tmp = new StringBuilder(40);//40 exceeds configured max token length
    for (int i = 0; i < 40; i++) {
      tmp.append((char)('0' + (i % 10)));
    }
    String SANFRAN = "San Francisco";
    buildNames(SANFRAN);
    assertTags(tmp.toString()+" "+SANFRAN, SANFRAN);
  }

  @Test
  /** whole matching, no sub-tags */
  public void testSingleWordPhrases() throws Exception {
    this.overlaps = "LONGEST_DOMINANT_RIGHT";

    buildNames("Greece", "Athens, Greece");

    assertTags("Greece.", "Greece");

  }
  
}
