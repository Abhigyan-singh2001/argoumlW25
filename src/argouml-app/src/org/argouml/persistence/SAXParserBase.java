/* $Id$
 *****************************************************************************
 * Copyright (c) 2009-2012 Contributors - see below
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    tfmorris
 *****************************************************************************
 *
 * Some portions of this file was previously release using the BSD License:
 */

// Copyright (c) 1996-2006 The Regents of the University of California. All
// Rights Reserved. Permission to use, copy, modify, and distribute this
// software and its documentation without fee, and without a written
// agreement is hereby granted, provided that the above copyright notice
// and this paragraph appear in all copies.  This software program and
// documentation are copyrighted by The Regents of the University of
// California. The software program and documentation are supplied "AS
// IS", without any accompanying services from The Regents. The Regents
// does not warrant that the operation of the program will be
// uninterrupted or error-free. The end-user understands that the program
// was developed for research purposes and is advised not to rely
// exclusively on the program for any reason.  IN NO EVENT SHALL THE
// UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
// SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
// ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
// THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF
// SUCH DAMAGE. THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE
// PROVIDED HEREUNDER IS ON AN "AS IS" BASIS, AND THE UNIVERSITY OF
// CALIFORNIA HAS NO OBLIGATIONS TO PROVIDE MAINTENANCE, SUPPORT,
// UPDATES, ENHANCEMENTS, OR MODIFICATIONS.

package org.argouml.persistence;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.helpers.DefaultHandler;

abstract class SAXParserBase extends DefaultHandler {
    private static final Logger LOG = Logger.getLogger(SAXParserBase.class.getName());

    public SAXParserBase() {
        // empty constructor
    }

    protected static final boolean DBG = false;
    private static XMLElement[] elements = new XMLElement[100];
    private static int nElements = 0;
    private static XMLElement[] freeElements = new XMLElement[100];
    private static int nFreeElements = 0;
    private static boolean stats = true;
    private static long parseTime = 0;

    public void setStats(boolean s) {
        stats = s;
    }

    public boolean getStats() {
        return stats;
    }

    public long getParseTime() {
        return parseTime;
    }

    public void parse(Reader reader) throws SAXException {
        parse(new InputSource(reader));
    }

    public void parse(InputSource input) throws SAXException {
        long start, end;

        SAXParserFactory factory = SAXParserFactory.newInstance();

        // Secure configuration against XXE
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException | SAXNotRecognizedException |
                 SAXNotSupportedException e) {
            throw new SAXException("Failed to configure XML parser securely against XXE", e);
        }

        factory.setNamespaceAware(false);
        factory.setValidating(false);

        try {
            SAXParser parser = factory.newSAXParser();

            if (input.getSystemId() == null) {
                input.setSystemId(getJarResource("org.argouml.kernel.Project"));
            }

            start = System.currentTimeMillis();
            parser.parse(input, this);
            end = System.currentTimeMillis();
            parseTime = end - start;
        } catch (IOException | ParserConfigurationException e) {
            throw new SAXException(e);
        }

        if (stats) {
            LOG.log(Level.INFO, "Elapsed time: {0} ms", (end - start));
        }
    }

    protected abstract void handleStartElement(XMLElement e) throws SAXException;

    protected abstract void handleEndElement(XMLElement e) throws SAXException;

    public void startElement(String uri, String localname, String name, Attributes atts) throws SAXException {
        if (isElementOfInterest(name)) {
            XMLElement element = createXmlElement(name, atts);

            if (LOG.isLoggable(Level.FINE)) {
                StringBuffer buf = new StringBuffer();
                buf.append("START: ").append(name).append(' ').append(element);
                for (int i = 0; i < atts.getLength(); i++) {
                    buf.append("   ATT: ")
                       .append(atts.getLocalName(i))
                       .append(' ')
                       .append(atts.getValue(i));
                }
                LOG.log(Level.FINE, "{0}", buf);
            }

            elements[nElements++] = element;
            handleStartElement(element);
        }
    }

    private XMLElement createXmlElement(String name, Attributes atts) {
        if (nFreeElements == 0) {
            return new XMLElement(name, atts);
        }
        XMLElement e = freeElements[--nFreeElements];
        e.setName(name);
        e.setAttributes(atts);
        e.resetText();
        return e;
    }

    public void endElement(String uri, String localname, String name) throws SAXException {
        if (isElementOfInterest(name)) {
            XMLElement e = elements[--nElements];
            if (LOG.isLoggable(Level.FINE)) {
                StringBuffer buf = new StringBuffer();
                buf.append("END: " + e.getName() + " [" + e.getText() + "] " + e + "\n");
                for (int i = 0; i < e.getNumAttributes(); i++) {
                    buf.append("   ATT: " + e.getAttributeName(i) + " " + e.getAttributeValue(i) + "\n");
                }
                LOG.log(Level.FINE, "{0}", buf);
            }
            handleEndElement(e);
        }
    }

    protected boolean isElementOfInterest(String name) {
        return true;
    }

    public void characters(char[] ch, int start, int length) throws SAXException {
        elements[nElements - 1].addText(ch, start, length);
    }

    public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
        try {
            URL testIt = new URL(systemId);
            InputSource s = new InputSource(testIt.openStream());
            return s;
        } catch (Exception e) {
            LOG.log(Level.INFO, "NOTE: Could not open DTD " + systemId + " due to exception");

            String dtdName = systemId.substring(systemId.lastIndexOf('/') + 1);
            String dtdPath = "/org/argouml/persistence/" + dtdName;
            InputStream is = SAXParserBase.class.getResourceAsStream(dtdPath);
            if (is == null) {
                try {
                    is = new FileInputStream(dtdPath.substring(1));
                } catch (Exception ex) {
                    throw new SAXException(e);
                }
            }
            return new InputSource(is);
        }
    }

    public String getJarResource(String cls) {
        String jarFile = "";
        String fileSep = System.getProperty("file.separator");
        String classFile = cls.replace('.', fileSep.charAt(0)) + ".class";
        ClassLoader thisClassLoader = this.getClass().getClassLoader();
        URL url = thisClassLoader.getResource(classFile);
        if (url != null) {
            String urlString = url.getFile();
            int idBegin = urlString.indexOf("file:");
            int idEnd = urlString.indexOf("!");
            if (idBegin > -1 && idEnd > -1 && idEnd > idBegin) {
                jarFile = urlString.substring(idBegin + 5, idEnd);
            }
        }
        return jarFile;
    }

    public void ignoreElement(XMLElement e) {
        LOG.log(Level.FINE, "NOTE: ignoring tag: {0}", e.getName());
    }

    public void notImplemented(XMLElement e) {
        LOG.log(Level.FINE, "NOTE: element not implemented: {0}", e.getName());
    }
} /* end class SAXParserBase */
