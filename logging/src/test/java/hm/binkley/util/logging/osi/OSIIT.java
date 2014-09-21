/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <http://unlicense.org/>.
 */

package hm.binkley.util.logging.osi;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.StandardOutputStreamLog;
import org.junit.rules.ExpectedException;
import org.slf4j.LoggerFactory;

import static hm.binkley.util.logging.LoggerUtil.refreshLogback;
import static hm.binkley.util.logging.osi.OSI.SystemProperty.LOGBACK_CONFIGURATION_FILE;
import static hm.binkley.util.logging.osi.OSI.SystemProperty.LOGBACK_JANSI;
import static hm.binkley.util.logging.osi.OSI.SystemProperty.LOGBACK_STYLES_RESOURCE;
import static hm.binkley.util.logging.osi.OSI.SystemProperty.resetForTesting;
import static java.lang.System.getProperty;
import static java.lang.System.setProperty;
import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.rules.ExpectedException.none;

/**
 * {@code OSIIT} tests {@link OSI}.
 *
 * @author <a href="mailto:Brian.Oxley@macquarie.com">Brian Oxley</a>
 * @todo StandardOutputStreamLog still prints to sout/serr
 * @todo StandardOutputStreamLog does not process into List of String
 */
public final class OSIIT {
    @Rule
    public final ExpectedException thrown = none();
    @Rule
    public final StandardOutputStreamLog sout = new StandardOutputStreamLog();

    @Before
    public void setUpOSIIT() {
        asList(OSI.SystemProperty.values()).stream().
                map(OSI.SystemProperty::key).
                filter(key -> null != getProperty(key)).
                forEach(System::clearProperty);
    }

    @After
    public void tearDownOSIIT() {
        resetForTesting();
    }

    @Test
    public void shouldSetUnset() {
        assertThat(getProperty("logback.configurationFile"), is(nullValue()));
        final String configurationFile = "ignored";
        LOGBACK_CONFIGURATION_FILE.set(configurationFile, false);
        assertThat(getProperty("logback.configurationFile"), is(equalTo(configurationFile)));
        LOGBACK_CONFIGURATION_FILE.unset();
        assertThat(getProperty("logback.configurationFile"), is(nullValue()));
    }

    @Test
    public void shouldThrowIfUnsetWithoutSet() {
        thrown.expect(IllegalStateException.class);

        LOGBACK_CONFIGURATION_FILE.unset();
    }

    @Test
    public void shouldThrowIfSetTwice() {
        thrown.expect(IllegalStateException.class);

        LOGBACK_CONFIGURATION_FILE.set("ignored", false);
        LOGBACK_CONFIGURATION_FILE.set("ignored", false);
    }

    @Test
    public void shouldNotThrowIfSetTwiceWithOverride() {
        LOGBACK_CONFIGURATION_FILE.set("ignored", false);
        final String configurationFile = "other ignored";
        LOGBACK_CONFIGURATION_FILE.set(configurationFile, true);
        assertThat(getProperty(LOGBACK_CONFIGURATION_FILE.key()), is(equalTo(configurationFile)));
    }

    @Test
    public void shouldIgnoreIfSystemPropertyExistsAndNotOverride() {
        setProperty(LOGBACK_CONFIGURATION_FILE.key(), "ignored");
        assertThat(LOGBACK_CONFIGURATION_FILE.set("ignored", false), is(false));
    }

    @Test
    public void shouldReturnTrueIfSystemPropertyExistsWithOverride() {
        setProperty(LOGBACK_CONFIGURATION_FILE.key(), "ignored");
        final String configurationFile = "other ignored";
        LOGBACK_CONFIGURATION_FILE.set(configurationFile, true);
        assertThat(getProperty(LOGBACK_CONFIGURATION_FILE.key()), is(equalTo(configurationFile)));
    }

    @Test
    public void shouldIncludeApplicationName() {
        OSI.enable("MyApp");
        LoggerFactory.getLogger("bob").error("ouch");
        assertThat(sout.getLog(), containsString("MyApp"));
    }

    @Test
    public void shouldIncludeAnsiEscapes() {
        setProperty(LOGBACK_JANSI.key(), "true");
        setProperty(LOGBACK_STYLES_RESOURCE.key(), "osi-logback-jansi-styles.properties");
        refreshLogback();
        LoggerFactory.getLogger("bob").error("broke");
        assertThat(sout.getLog(), is(equalTo("broke")));
    }
}