/*
  This file is licensed to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package org.xmlunit.diff;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import org.xmlunit.XMLUnitException;
import org.xmlunit.util.Convert;
import org.xmlunit.util.IterableNodeList;
import org.xmlunit.util.Linqy;
import org.xmlunit.util.Nodes;
import org.xmlunit.util.Predicate;
import org.w3c.dom.Attr;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;

/**
 * Difference engine based on DOM.
 */
public final class DOMDifferenceEngine extends AbstractDifferenceEngine {

    @Override
    public void compare(Source control, Source test) {
        if (control == null) {
            throw new IllegalArgumentException("control must not be null");
        }
        if (test == null) {
            throw new IllegalArgumentException("test must not be null");
        }
        try {
            Node controlNode = Convert.toNode(control);
            Node testNode = Convert.toNode(test);
            compareNodes(controlNode, xpathContextFor(controlNode),
                         testNode, xpathContextFor(testNode));
        } catch (Exception ex) {
            throw new XMLUnitException("Caught exception during comparison",
                                       ex);
        }
    }

    private XPathContext xpathContextFor(Node n) {
        return new XPathContext(getNamespaceContext(), n);
    }

    /**
     * Recursively compares two XML nodes.
     *
     * <p>Performs comparisons common to all node types, then performs
     * the node type specific comparisons and finally recurses into
     * the node's child lists.</p>
     *
     * <p>Stops as soon as any comparison returns
     * ComparisonResult.CRITICAL.</p>
     *
     * <p>package private to support tests.</p>
     */
    Map.Entry<ComparisonResult, Boolean>
        compareNodes(final Node control, final XPathContext controlContext,
                     final Node test, final XPathContext testContext) {
        final Iterable<Node> controlChildren =
            Linqy.filter(new IterableNodeList(control.getChildNodes()),
                         INTERESTING_NODES);
        final Iterable<Node> testChildren =
            Linqy.filter(new IterableNodeList(test.getChildNodes()),
                         INTERESTING_NODES);

        return new ComparisonChain(
            compare(new Comparison(ComparisonType.NODE_TYPE,
                                   control, getXPath(controlContext),
                                   control.getNodeType(),
                                   test, getXPath(testContext),
                                   test.getNodeType())))
            .andThen(comparer(new Comparison(ComparisonType.NAMESPACE_URI,
                                             control, getXPath(controlContext),
                                             control.getNamespaceURI(),
                                             test, getXPath(testContext),
                                             test.getNamespaceURI())))
            .andThen(comparer(new Comparison(ComparisonType.NAMESPACE_PREFIX,
                                             control, getXPath(controlContext),
                                             control.getPrefix(),
                                             test, getXPath(testContext),
                                             test.getPrefix())))
            .andIfTrueThen(control.getNodeType() != Node.ATTRIBUTE_NODE,
                           comparer(new Comparison(ComparisonType.CHILD_NODELIST_LENGTH,
                                                   control, getXPath(controlContext),
                                                   Linqy.count(controlChildren),
                                                   test, getXPath(testContext),
                                                   Linqy.count(testChildren))))
            .andThen(new DeferredComparison() {
                    @Override
                    public Map.Entry<ComparisonResult, Boolean> apply() {
                        return nodeTypeSpecificComparison(control, controlContext,
                                                          test, testContext);
                    }
                })
            // and finally recurse into children
            .andIfTrueThen(control.getNodeType() != Node.ATTRIBUTE_NODE,
                           compareChildren(control, controlContext,
                                           controlChildren,
                                           test, testContext,
                                           testChildren))
            .getFinalResult();
    }

    /**
     * Dispatches to the node type specific comparison if one is
     * defined for the given combination of nodes.
     */
    private Map.Entry<ComparisonResult, Boolean>
        nodeTypeSpecificComparison(Node control,
                                   XPathContext controlContext,
                                   Node test, XPathContext testContext) {
        switch (control.getNodeType()) {
        case Node.CDATA_SECTION_NODE:
        case Node.COMMENT_NODE:
        case Node.TEXT_NODE:
            if (test instanceof CharacterData) {
                return compareCharacterData((CharacterData) control,
                                            controlContext,
                                            (CharacterData) test, testContext);
            }
            break;
        case Node.DOCUMENT_NODE:
            if (test instanceof Document) {
                return compareDocuments((Document) control, controlContext,
                                        (Document) test, testContext);
            }
            break;
        case Node.ELEMENT_NODE:
            if (test instanceof Element) {
                return compareElements((Element) control, controlContext,
                                       (Element) test, testContext);
            }
            break;
        case Node.PROCESSING_INSTRUCTION_NODE:
            if (test instanceof ProcessingInstruction) {
                return
                    compareProcessingInstructions((ProcessingInstruction) control,
                                                  controlContext,
                                                  (ProcessingInstruction) test,
                                                  testContext);
            }
            break;
        case Node.DOCUMENT_TYPE_NODE:
            if (test instanceof DocumentType) {
                return compareDocTypes((DocumentType) control, controlContext,
                                       (DocumentType) test, testContext);
            }
            break;
        case Node.ATTRIBUTE_NODE:
            if (test instanceof Attr) {
                return compareAttributes((Attr) control, controlContext,
                                         (Attr) test, testContext);
            }
            break;
        }
        return new AbstractMap.SimpleImmutableEntry(ComparisonResult.EQUAL, false);
    }

    private DeferredComparison compareChildren(final Node control,
                                               final XPathContext controlContext,
                                               final Iterable<Node> controlChildren,
                                               final Node test,
                                               final XPathContext testContext,
                                               final Iterable<Node> testChildren) {
        return new DeferredComparison() {
            @Override
            public Map.Entry<ComparisonResult, Boolean> apply() {
                controlContext
                    .setChildren(Linqy.map(controlChildren, TO_NODE_INFO));
                testContext
                    .setChildren(Linqy.map(testChildren, TO_NODE_INFO));
                return compareNodeLists(controlChildren, controlContext,
                                        testChildren, testContext);
            }
        };
    }

    /**
     * Compares textual content.
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareCharacterData(CharacterData control,
                             XPathContext controlContext,
                             CharacterData test,
                             XPathContext testContext) {
        return compare(new Comparison(ComparisonType.TEXT_VALUE, control,
                                      getXPath(controlContext),
                                      control.getData(),
                                      test, getXPath(testContext),
                                      test.getData()));
    }

    /**
     * Compares document node, doctype and XML declaration properties
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareDocuments(final Document control,
                         final XPathContext controlContext,
                         final Document test,
                         final XPathContext testContext) {
        final DocumentType controlDt = control.getDoctype();
        final DocumentType testDt = test.getDoctype();

        return new ComparisonChain(
            compare(new Comparison(ComparisonType.HAS_DOCTYPE_DECLARATION,
                                   control, getXPath(controlContext),
                                   Boolean.valueOf(controlDt != null),
                                   test, getXPath(testContext),
                                   Boolean.valueOf(testDt != null))))
            .andIfTrueThen(controlDt != null && testDt != null,
                           new DeferredComparison() {
                               @Override
                               public Map.Entry<ComparisonResult, Boolean> apply() {
                                   return compareNodes(controlDt, controlContext,
                                                       testDt, testContext);
                               }
                           })
            .andThen(compareDeclarations(control, controlContext,
                                         test, testContext))
            .getFinalResult();
    }

    /**
     * Compares properties of the doctype declaration.
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareDocTypes(DocumentType control,
                        XPathContext controlContext,
                        DocumentType test,
                        XPathContext testContext) {
        return new ComparisonChain(
            compare(new Comparison(ComparisonType.DOCTYPE_NAME,
                                   control, getXPath(controlContext),
                                   control.getName(),
                                   test, getXPath(testContext),
                                   test.getName())))
            .andThen(comparer(new Comparison(ComparisonType.DOCTYPE_PUBLIC_ID,
                                             control, getXPath(controlContext),
                                             control.getPublicId(),
                                             test, getXPath(testContext),
                                             test.getPublicId())))
            .andThen(comparer(new Comparison(ComparisonType.DOCTYPE_SYSTEM_ID,
                                             control, null, control.getSystemId(),
                                             test, null, test.getSystemId())))
            .getFinalResult();
    }

    /**
     * Compares properties of XML declaration.
     */
    private DeferredComparison compareDeclarations(final Document control,
                                                   final XPathContext controlContext,
                                                   final Document test,
                                                   final XPathContext testContext) {
        return new DeferredComparison() {
            @Override
            public Map.Entry<ComparisonResult, Boolean> apply() {
                return new ComparisonChain(
                    compare(new Comparison(ComparisonType.XML_VERSION,
                                           control, getXPath(controlContext),
                                           control.getXmlVersion(),
                                           test, getXPath(testContext),
                                           test.getXmlVersion())))
                    .andThen(comparer(new Comparison(ComparisonType.XML_STANDALONE,
                                                     control, getXPath(controlContext),
                                                     control.getXmlStandalone(),
                                                     test, getXPath(testContext),
                                                     test.getXmlStandalone())))
                    .andThen(comparer(new Comparison(ComparisonType.XML_ENCODING,
                                                     control, getXPath(controlContext),
                                                     control.getXmlEncoding(),
                                                     test, getXPath(testContext),
                                                     test.getXmlEncoding())))
                    .getFinalResult();
            }
        };
    }

    /**
     * Compares elements node properties, in particular the element's
     * name and its attributes.
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareElements(final Element control,
                        final XPathContext controlContext,
                        final Element test,
                        final XPathContext testContext) {
        return new ComparisonChain(
            compare(new Comparison(ComparisonType.ELEMENT_TAG_NAME,
                                   control, getXPath(controlContext),
                                   Nodes.getQName(control).getLocalPart(),
                                   test, getXPath(testContext),
                                   Nodes.getQName(test).getLocalPart())))
            .andThen(new DeferredComparison() {
                    @Override
                    public Map.Entry<ComparisonResult, Boolean> apply() {
                        return compareElementAttributes(control, controlContext,
                                                        test, testContext);
                    }
                })
            .getFinalResult();
    }

    /**
     * Compares element's attributes.
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareElementAttributes(final Element control,
                                 final XPathContext controlContext,
                                 final Element test,
                                 final XPathContext testContext) {
        final Attributes controlAttributes = splitAttributes(control.getAttributes());
        controlContext
            .addAttributes(Linqy.map(controlAttributes.remainingAttributes,
                                     QNAME_MAPPER));
        final Attributes testAttributes = splitAttributes(test.getAttributes());
        testContext
            .addAttributes(Linqy.map(testAttributes.remainingAttributes,
                                     QNAME_MAPPER));

        return new ComparisonChain(
            compare(new Comparison(ComparisonType.ELEMENT_NUM_ATTRIBUTES,
                                   control, getXPath(controlContext),
                                   controlAttributes.remainingAttributes.size(),
                                   test, getXPath(testContext),
                                   testAttributes.remainingAttributes.size())))
            .andThen(new DeferredComparison() {
                    @Override
                    public Map.Entry<ComparisonResult, Boolean> apply() {
                        return compareXsiType(controlAttributes.type, controlContext,
                                              testAttributes.type, testContext);
                    }
                })
            .andThen(comparer(new Comparison(ComparisonType.SCHEMA_LOCATION,
                                             control, getXPath(controlContext),
                                             controlAttributes.schemaLocation != null
                                             ? controlAttributes.schemaLocation.getValue()
                                             : null,
                                             test, getXPath(testContext),
                                             testAttributes.schemaLocation != null
                                             ? testAttributes.schemaLocation.getValue()
                                             : null)))
            .andThen(comparer(new Comparison(ComparisonType.NO_NAMESPACE_SCHEMA_LOCATION,
                                             control, getXPath(controlContext),
                                             controlAttributes.noNamespaceSchemaLocation != null ?
                                             controlAttributes.noNamespaceSchemaLocation.getValue()
                                             : null,
                                             test, getXPath(testContext),
                                             testAttributes.noNamespaceSchemaLocation != null
                                             ? testAttributes.noNamespaceSchemaLocation.getValue()
                                             : null)))
            .andThen(new NormalAttributeComparer(control, controlContext,
                                                 controlAttributes, test,
                                                 testContext, testAttributes))
            .getFinalResult();
    }

    private class NormalAttributeComparer implements DeferredComparison {
        private final Set<Attr> foundTestAttributes = new HashSet<Attr>();
        private final Element control, test;
        private final XPathContext controlContext, testContext;
        private final Attributes controlAttributes, testAttributes;

        private NormalAttributeComparer(Element control,
                                        XPathContext controlContext,
                                        Attributes controlAttributes,
                                        Element test,
                                        XPathContext testContext,
                                        Attributes testAttributes) {
            this.control = control;
            this.controlContext = controlContext;
            this.controlAttributes = controlAttributes;
            this.test = test;
            this.testContext = testContext;
            this.testAttributes = testAttributes;
        }

        @Override
        public Map.Entry<ComparisonResult, Boolean> apply() {
            ComparisonChain chain = new ComparisonChain();
            for (final Attr controlAttr : controlAttributes.remainingAttributes) {
                final QName controlAttrName = Nodes.getQName(controlAttr);
                final Attr testAttr =
                    findMatchingAttr(testAttributes.remainingAttributes,
                                     controlAttr);
                final QName testAttrName = testAttr != null
                    ? Nodes.getQName(testAttr) : null;

                controlContext.navigateToAttribute(controlAttrName);
                try {
                    chain.andThen(
                        comparer(new Comparison(ComparisonType.ATTR_NAME_LOOKUP,
                                                control, getXPath(controlContext),
                                                controlAttrName,
                                                test, getXPath(testContext),
                                                testAttrName)));

                    if (testAttr != null) {
                        testContext.navigateToAttribute(testAttrName);
                        try {
                            chain.andThen(new DeferredComparison() {
                                    @Override
                                    public Map.Entry<ComparisonResult, Boolean> apply() {
                                        return compareNodes(controlAttr,
                                                            controlContext,
                                                            testAttr,
                                                            testContext);
                                    }
                                });
                            foundTestAttributes.add(testAttr);
                        } finally {
                            testContext.navigateToParent();
                        }
                    }
                } finally {
                    controlContext.navigateToParent();
                }
            }
            return chain.andThen(new ControlAttributePresentComparer(control,
                                                                     controlContext,
                                                                     test, testContext,
                                                                     testAttributes,
                                                                     foundTestAttributes))
                .getFinalResult();
        }
    }
    
    private class ControlAttributePresentComparer implements DeferredComparison {

        private final Set<Attr> foundTestAttributes;
        private final Element control, test;
        private final XPathContext controlContext, testContext;
        private final Attributes testAttributes;

        private ControlAttributePresentComparer(Element control,
                                                XPathContext controlContext,
                                                Element test,
                                                XPathContext testContext,
                                                Attributes testAttributes,
                                                Set<Attr> foundTestAttributes) {
            this.control = control;
            this.controlContext = controlContext;
            this.test = test;
            this.testContext = testContext;
            this.testAttributes = testAttributes;
            this.foundTestAttributes = foundTestAttributes;
        }

        @Override
        public Map.Entry<ComparisonResult, Boolean> apply() {
            ComparisonChain chain = new ComparisonChain();
            for (Attr testAttr : testAttributes.remainingAttributes) {
                if (!foundTestAttributes.contains(testAttr)) {
                    QName testAttrName = Nodes.getQName(testAttr);
                    testContext.navigateToAttribute(testAttrName);
                    try {
                        chain.andThen(comparer(new Comparison(ComparisonType.ATTR_NAME_LOOKUP,
                                                              control,
                                                              getXPath(controlContext),
                                                              null,
                                                              test, getXPath(testContext),
                                                              testAttrName)));
                    } finally {
                        testContext.navigateToParent();
                    }
                }
            }
            return chain.getFinalResult();
        }

    }

    /**
     * Compares properties of a processing instruction.
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareProcessingInstructions(ProcessingInstruction control,
                                      XPathContext controlContext,
                                      ProcessingInstruction test,
                                      XPathContext testContext) {
        return new ComparisonChain(
            compare(new Comparison(ComparisonType.PROCESSING_INSTRUCTION_TARGET,
                                   control, getXPath(controlContext),
                                   control.getTarget(),
                                   test, getXPath(testContext),
                                   test.getTarget())))
            .andThen(comparer(new Comparison(ComparisonType.PROCESSING_INSTRUCTION_DATA,
                                             control, getXPath(controlContext),
                                             control.getData(),
                                             test, getXPath(testContext),
                                             test.getData())))
            .getFinalResult();
    }

    /**
     * Matches nodes of two node lists and invokes compareNode on each pair.
     *
     * <p>Also performs CHILD_LOOKUP comparisons for each node that
     * couldn't be matched to one of the "other" list.</p>
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareNodeLists(Iterable<Node> controlSeq,
                         final XPathContext controlContext,
                         Iterable<Node> testSeq,
                         final XPathContext testContext) {
        ComparisonChain chain = new ComparisonChain();

        Iterable<Map.Entry<Node, Node>> matches =
            getNodeMatcher().match(controlSeq, testSeq);
        List<Node> controlList = Linqy.asList(controlSeq);
        List<Node> testList = Linqy.asList(testSeq);
        Set<Node> seen = new HashSet<Node>();
        for (Map.Entry<Node, Node> pair : matches) {
            final Node control = pair.getKey();
            seen.add(control);
            final Node test = pair.getValue();
            seen.add(test);
            int controlIndex = controlList.indexOf(control);
            int testIndex = testList.indexOf(test);

            controlContext.navigateToChild(controlIndex);
            testContext.navigateToChild(testIndex);
            try {
                chain.andThen(comparer(new Comparison(ComparisonType.CHILD_NODELIST_SEQUENCE,
                                                      control, getXPath(controlContext),
                                                      Integer.valueOf(controlIndex),
                                                      test, getXPath(testContext),
                                                      Integer.valueOf(testIndex))))
                    .andThen(new DeferredComparison() {
                            @Override
                            public Map.Entry<ComparisonResult, Boolean> apply() {
                                return compareNodes(control, controlContext,
                                                    test, testContext);
                            }
                        });
            } finally {
                testContext.navigateToParent();
                controlContext.navigateToParent();
            }
        }

        return chain.andThen(new UnmatchedControlNodes(controlList, controlContext, seen))
            .andThen(new UnmatchedTestNodes(testList, testContext, seen))
            .getFinalResult();
    }

    private class UnmatchedControlNodes implements DeferredComparison {
        private final List<Node> controlList;
        private final XPathContext controlContext;
        private final Set<Node> seen;
        
        private UnmatchedControlNodes(List<Node> controlList, XPathContext controlContext,
                                      Set<Node> seen) {
            this.controlList = controlList;
            this.controlContext = controlContext;
            this.seen = seen;
        }

        @Override
        public Map.Entry<ComparisonResult, Boolean> apply() {
            ComparisonChain chain = new ComparisonChain();
            final int controlSize = controlList.size();
            for (int i = 0; i < controlSize; i++) {
                if (!seen.contains(controlList.get(i))) {
                    controlContext.navigateToChild(i);
                    try {
                        chain.andThen(comparer(new Comparison(ComparisonType.CHILD_LOOKUP,
                                                              controlList.get(i),
                                                              getXPath(controlContext),
                                                              Nodes.getQName(controlList
                                                                             .get(i)),
                                                              null, null, null)));
                    } finally {
                        controlContext.navigateToParent();
                    }
                }
            }
            return chain.getFinalResult();
        }
    }
        
    private class UnmatchedTestNodes implements DeferredComparison {
        private final List<Node> testList;
        private final XPathContext testContext;
        private final Set<Node> seen;
        
        private UnmatchedTestNodes(List<Node> testList, XPathContext testContext,
                                      Set<Node> seen) {
            this.testList = testList;
            this.testContext = testContext;
            this.seen = seen;
        }

        @Override
        public Map.Entry<ComparisonResult, Boolean> apply() {
            ComparisonChain chain = new ComparisonChain();
            final int testSize = testList.size();
            for (int i = 0; i < testSize; i++) {
                if (!seen.contains(testList.get(i))) {
                    testContext.navigateToChild(i);
                    try {
                        chain.andThen(comparer(new Comparison(ComparisonType.CHILD_LOOKUP,
                                                              null, null, null,
                                                              testList.get(i),
                                                              getXPath(testContext),
                                                              Nodes.getQName(testList
                                                                             .get(i)))));
                    } finally {
                        testContext.navigateToParent();
                    }
                }
            }
            return chain.getFinalResult();
        }
    }

    /**
     * Compares xsi:type attribute values
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareXsiType(Attr controlAttr,
                       XPathContext controlContext,
                       Attr testAttr,
                       XPathContext testContext) {
        boolean mustChangeControlContext = controlAttr != null;
        boolean mustChangeTestContext = testAttr != null;
        if (!mustChangeControlContext && !mustChangeTestContext) {
            return new AbstractMap.SimpleImmutableEntry(ComparisonResult.EQUAL, false);
        }
        boolean attributePresentOnBothSides = mustChangeControlContext
            && mustChangeTestContext;

        try {
            QName controlAttrName = null;
            if (mustChangeControlContext) {
                controlAttrName = Nodes.getQName(controlAttr);
                controlContext.addAttribute(controlAttrName);
                controlContext.navigateToAttribute(controlAttrName);
            }
            QName testAttrName = null;
            if (mustChangeTestContext) {
                testAttrName = Nodes.getQName(testAttr);
                testContext.addAttribute(testAttrName);
                testContext.navigateToAttribute(testAttrName);
            }
            return new ComparisonChain(
                compare(new Comparison(ComparisonType.ATTR_NAME_LOOKUP,
                                       controlAttr, getXPath(controlContext),
                                       controlAttrName,
                                       testAttr, getXPath(testContext),
                                       testAttrName)))
                .andIfTrueThen(attributePresentOnBothSides,
                               compareAttributeExplicitness(controlAttr, controlContext,
                                                            testAttr, testContext))
                .andIfTrueThen(attributePresentOnBothSides,
                               comparer(new Comparison(ComparisonType.ATTR_VALUE,
                                                       controlAttr,
                                                       getXPath(controlContext),
                                                       valueAsQName(controlAttr),
                                                       testAttr,
                                                       getXPath(testContext),
                                                       valueAsQName(testAttr))))
                .getFinalResult();
        } finally {
            if (mustChangeControlContext) {
                controlContext.navigateToParent();
            }
            if (mustChangeTestContext) {
                testContext.navigateToParent();
            }
        }
    }

    /**
     * Compares properties of an attribute.
     */
    private Map.Entry<ComparisonResult, Boolean>
        compareAttributes(Attr control,
                          XPathContext controlContext,
                          Attr test,
                          XPathContext testContext) {
        return new ComparisonChain(
            compareAttributeExplicitness(control, controlContext, test,
                                         testContext).apply())
            .andThen(comparer(new Comparison(ComparisonType.ATTR_VALUE,
                                             control, getXPath(controlContext),
                                             control.getValue(),
                                             test, getXPath(testContext),
                                             test.getValue())))
            .getFinalResult();
    }

    /**
     * Compares whether two attributes are specified explicitly.
     */
    private DeferredComparison
        compareAttributeExplicitness(Attr control, XPathContext controlContext,
                                     Attr test, XPathContext testContext) {
        return
            comparer(new Comparison(ComparisonType.ATTR_VALUE_EXPLICITLY_SPECIFIED,
                                   control, getXPath(controlContext),
                                   control.getSpecified(),
                                   test, getXPath(testContext),
                                   test.getSpecified()));
    }

    /**
     * Separates XML namespace related attributes from "normal" attributes.xb
     */
    private static Attributes splitAttributes(final NamedNodeMap map) {
        Attr sLoc = (Attr) map.getNamedItemNS(XMLConstants
                                              .W3C_XML_SCHEMA_INSTANCE_NS_URI,
                                              "schemaLocation");
        Attr nNsLoc = (Attr) map.getNamedItemNS(XMLConstants
                                                .W3C_XML_SCHEMA_INSTANCE_NS_URI,
                                                "noNamespaceSchemaLocation");
        Attr type = (Attr) map.getNamedItemNS(XMLConstants
                                                .W3C_XML_SCHEMA_INSTANCE_NS_URI,
                                                "type");
        List<Attr> rest = new LinkedList<Attr>();
        final int len = map.getLength();
        for (int i = 0; i < len; i++) {
            Attr a = (Attr) map.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())
                && a != sLoc && a != nNsLoc && a != type) {
                rest.add(a);
            }
        }
        return new Attributes(sLoc, nNsLoc, type, rest);
    }

    private static QName valueAsQName(Attr attribute) {
        // split QName into prefix and local name
        String[] pieces = attribute.getValue().split(":");
        if (pieces.length < 2) {
            // unprefixed name
            pieces = new String[] { null, pieces[0] };
        } else if (pieces.length > 2) {
            // actually, this is not a valid QName - be lenient
            pieces = new String[] {
                pieces[0],
                attribute.getValue().substring(pieces[0].length() + 1)
            };
        }
        if ("".equals(pieces[0])) {
            pieces[0] = null;
        }
        return new QName(attribute.lookupNamespaceURI(pieces[0]), pieces[1]);
    }

    private static class Attributes {
        private final Attr schemaLocation;
        private final Attr noNamespaceSchemaLocation;
        private final Attr type;
        private final List<Attr> remainingAttributes;
        private Attributes(Attr schemaLocation, Attr noNamespaceSchemaLocation,
                           Attr type, List<Attr> remainingAttributes) {
            this.schemaLocation = schemaLocation;
            this.noNamespaceSchemaLocation = noNamespaceSchemaLocation;
            this.type = type;
            this.remainingAttributes = remainingAttributes;
        }
    }

    /**
     * Find the attribute with the same namespace and local name as a
     * given attribute in a list of attributes.
     */
    private static Attr findMatchingAttr(final List<Attr> attrs,
                                         final Attr attrToMatch) {
        final boolean hasNs = attrToMatch.getNamespaceURI() != null;
        final String nsToMatch = attrToMatch.getNamespaceURI();
        final String nameToMatch = hasNs ? attrToMatch.getLocalName()
            : attrToMatch.getName();
        for (Attr a : attrs) {
            if (((!hasNs && a.getNamespaceURI() == null)
                 ||
                 (hasNs && nsToMatch.equals(a.getNamespaceURI())))
                &&
                ((hasNs && nameToMatch.equals(a.getLocalName()))
                 ||
                 (!hasNs && nameToMatch.equals(a.getName())))
                ) {
                return a;
            }
        }
        return null;
    }

    /**
     * Maps Nodes to their QNames.
     */
    private static final Linqy.Mapper<Node, QName> QNAME_MAPPER =
        new Linqy.Mapper<Node, QName>() {
        public QName map(Node n) { return Nodes.getQName(n); }
    };

    /**
     * Maps Nodes to their NodeInfo equivalent.
     */
    private static final Linqy.Mapper<Node, XPathContext.NodeInfo> TO_NODE_INFO =
        new Linqy.Mapper<Node, XPathContext.NodeInfo>() {
        public XPathContext.NodeInfo map(Node n) {
            return new XPathContext.DOMNodeInfo(n);
        }
    };

    /**
     * Suppresses document-type nodes.
     */
    private static final Predicate<Node> INTERESTING_NODES =
        new Predicate<Node>() {
        public boolean matches(Node n) {
            return n.getNodeType() != Node.DOCUMENT_TYPE_NODE;
        }
    };

}
