package com.hsbc.cranker.mucranker;


import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class MuCrankerTest {

    @Test
    public void gettingVersionDoesNotThrowException() {
        assertThat(MuCranker.artifactVersion(), containsString("."));
    }

}
