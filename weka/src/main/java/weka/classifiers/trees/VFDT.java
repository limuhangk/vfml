package weka.classifiers.trees;

import static com.metsci.glimpse.util.logging.LoggerUtils.logWarning;

import java.util.Enumeration;
import java.util.logging.Logger;

import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.Capabilities;
import weka.core.Capabilities.Capability;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.NoSupportForMissingValuesException;
import weka.core.TechnicalInformation;
import weka.core.TechnicalInformation.Field;
import weka.core.TechnicalInformation.Type;
import weka.core.TechnicalInformationHandler;
import weka.core.Utils;
import edu.gmu.vfml.tree.Node;

/**
 * 
 * @see weka.classifiers.trees.Id3
 * @author ulman
 */
public class VFDT extends Classifier implements TechnicalInformationHandler
{
    private static final Logger logger = Logger.getLogger( VFDT.class.getName( ) );

    private static final long serialVersionUID = 1L;

    /** Root node of classification tree. */
    private Node root;

    private Attribute classAttribute;
    private int numClasses;

    private double R_squared; // log2( numClasses )^2 
    private double ln_inv_delta; // ln( 1 / delta )

    // if the hoeffding bound drops below tie confidence, assume the best two attributes
    // are very similar (and thus might require an extremely large number of instances
    // to separate with high confidence), so just choose the current best
    private double tieConfidence = 0.05;
    // 1-delta is the probability of choosing the correct attribute at any given node
    private double delta;

    /**
     * Returns the tip text for this property.
     * @return tip text for this property suitable for
     * displaying in the explorer/experimenter gui
     */
    public String confidenceLevelTipText( )
    {
        return "One minus the probability that each attribute split in " + "the VFDT tree will be the same as a batch generated tree.";
    }

    /**
     * See equation (1) in "Mining High-Speed Data Streams." The Hoeffding Bound provides
     * a bound on the true mean of a random variable given n independent
     * observations of the random variable, with probability 1 - delta
     * (where delta is the confidence level returned by this method).
     * 
     * @return the Hoeffding Bound confidence level
     */
    public double getConfidenceLevel( )
    {
        return delta;
    }

    /**
     * @see #getConfidenceLevel( )
     * @param delta
     */
    public void setConfidenceLevel( double delta )
    {
        this.delta = delta;
        this.ln_inv_delta = Math.log( 1 / delta );
    }

    /**
     * Returns the tip text for this property.
     * @return tip text for this property suitable for
     * displaying in the explorer/experimenter gui
     */
    public String tieConfidenceTipText( )
    {
        return "If the Hoeffding bound falls below this value, the node will " + "be automatically split on the current best attribute. This" + "prevents nodes with two very similar attributes from taking " + "excessiely many instances to split with high confidence.";
    }

    /**
     * If two attributes have very similar information gain, then
     * it may take many instances to choose between them with
     * high confidence. Tie confidence sets an alternative threshold
     * which causes a split decision to be automatically made if the
     * Hoeffding bound drops below the tie confidence.
     * 
     * @return
     */
    public double getTieConfidence( )
    {
        return this.tieConfidence;
    }

    /**
     * #see {@link #getConfidenceLevel()}
     * @param tieConfidence
     */
    public void setTieConfidence( double tieConfidence )
    {
        this.tieConfidence = tieConfidence;
    }

    /**
     * Returns a string describing the classifier.
     * @return a description suitable for the GUI.
     */
    public String globalInfo( )
    {
        //@formatter:off
        return "Class for constructing an unpruned decision tree based on the VFDT " +
               "algorithm. For more information see: \n\n" +
               getTechnicalInformation( ).toString( );
        //@formatter:on
    }

    /**
     * Returns an instance of a TechnicalInformation object, containing 
     * detailed information about the technical background of this class,
     * e.g., paper reference or book this class is based on.
     * 
     * @return the technical information about this class
     */
    public TechnicalInformation getTechnicalInformation( )
    {
        TechnicalInformation info = new TechnicalInformation( Type.ARTICLE );

        info.setValue( Field.AUTHOR, "Domingos, Pedro" );
        info.setValue( Field.YEAR, "2000" );
        info.setValue( Field.TITLE, "Mining high-speed data streams" );
        info.setValue( Field.JOURNAL, "Proceedings of the sixth ACM SIGKDD international conference on Knowledge discovery and data mining" );
        info.setValue( Field.SERIES, "KDD '00" );
        info.setValue( Field.ISBN, "1-58113-233-6" );
        info.setValue( Field.LOCATION, "Boston, Massachusetts, USA" );
        info.setValue( Field.PAGES, "71-80" );
        info.setValue( Field.URL, "http://doi.acm.org/10.1145/347090.347107" );
        info.setValue( Field.PUBLISHER, "ACM" );

        return info;
    }

    /**
     * Returns default capabilities of the classifier.
     *
     * @return the capabilities of this classifier
     */
    public Capabilities getCapabilities( )
    {
        Capabilities result = super.getCapabilities( );
        result.disableAll( );

        // attributes
        result.enable( Capability.NOMINAL_ATTRIBUTES );

        // class
        result.enable( Capability.NOMINAL_CLASS );
        result.enable( Capability.MISSING_CLASS_VALUES );

        // instances
        result.setMinimumNumberInstances( 0 );

        return result;
    }

    /**
     * Classifies a given test instance using the decision tree.
     *
     * @param instance the instance to be classified
     * @return the classification
     * @throws NoSupportForMissingValuesException if instance has missing values
     * @see weka.classifiers.trees.Id3#classifyInstance(Instance)
     */
    public double classifyInstance( Instance instance ) throws NoSupportForMissingValuesException
    {
        if ( instance.hasMissingValue( ) )
        {
            throw new NoSupportForMissingValuesException( "Id3: missing values not supported." );
        }

        // get the class value for the leaf node corresponding to the provided instance
        return getLeafNode( root, instance ).getClasValue( );
    }

    /**
     * Builds Id3 decision tree classifier.
     *
     * @param data the training data
     * @exception Exception if classifier can't be built successfully
     */
    @Override
    public void buildClassifier( Instances data ) throws Exception
    {
        // can classifier handle the data?
        getCapabilities( ).testWithFail( data );

        // remove instances with missing class
        data = new Instances( data );
        data.deleteWithMissingClass( );

        // store the class attribute for the data set
        classAttribute = data.classAttribute( );

        // record number of class values, attributes, and values for each attribute
        numClasses = data.classAttribute( ).numValues( );
        R_squared = Math.pow( Utils.log2( numClasses ), 2 );

        // cap confidence level
        if ( delta <= 0 || delta > 1 )
        {
            setConfidenceLevel( 0.05 );
        }

        // create root node
        root = newNode( data );

        // build the Hoeffding tree
        makeTree( data );
    }

    private Node newNode( Instances instances )
    {
        return new Node( instances, classAttribute );
    }

    /**
     * Method for building an Id3 tree.
     *
     * @param data the training data
     * @exception Exception if decision tree can't be built successfully
     */
    private void makeTree( Instances data ) throws Exception
    {
        makeTree( data.enumerateInstances( ) );
    }

    @SuppressWarnings( "rawtypes" )
    private void makeTree( Enumeration data )
    {
        while ( data.hasMoreElements( ) )
        {
            // retrieve the next data instance
            Instance instance = ( Instance ) data.nextElement( );

            // traverse the classification tree to find the leaf node for this instance
            Node node = getLeafNode( instance );

            // update the counts associated with this instance
            node.incrementCounts( instance );

            // determine based on Hoeffding Bound whether to split node
            int firstIndex = 0;
            double firstValue = Double.MAX_VALUE;
            double secondValue = Double.MAX_VALUE;

            try
            {
                // loop through all the attributes, calculating information gains
                // and keeping the attributes with the two highest information gains
                for ( int attrIndex = 0; attrIndex < instance.numAttributes( ); attrIndex++ )
                {
                    // don't consider the class attribute
                    if ( attrIndex == classAttribute.index( ) ) continue;

                    Attribute attribute = instance.attribute( attrIndex );
                    double value = computeEntropySum( node, attribute );

                    if ( value < firstValue )
                    {
                        secondValue = firstValue;
                        firstValue = value;
                        firstIndex = attrIndex;
                    }
                    else if ( value < secondValue )
                    {
                        secondValue = value;
                    }
                }
            }
            catch ( Exception e )
            {
                logWarning( logger, "Trouble calculating information gain.", e );
            }

            // if the difference between the information gain of the two best attributes
            // has exceeded the Hoeffding bound (which will continually shrink as more
            // attributes are added to the node) then split on the best attribute 
            double hoeffdingBound = calculateHoeffdingBound( node );

            if ( node.getCount( ) % 1000 == 0 )
            {
                System.out.printf( "%f %f %d%n", secondValue - firstValue, hoeffdingBound, node.getCount( ) );
            }

            boolean tie = tieConfidence > hoeffdingBound;
            boolean confident = secondValue - firstValue > hoeffdingBound;

            // see: vfdt-engine.c:871
            if ( tie || confident )
            {
                Attribute attribute = instance.attribute( firstIndex );
                node.split( attribute, instance );
            }
        }
    }

    /**
     * Computes information gain for an attribute.
     *
     * @param data the data for which info gain is to be computed
     * @param att the attribute
     * @return the information gain for the given attribute and data
     * @throws Exception if computation fails
     * @see weka.classifiers.trees.Id3#computeInfoGain( Instances, Attribute )
     */
    @SuppressWarnings( "unused" )
    private double computeInfoGain( Node node, Attribute attr ) throws Exception
    {
        return computeEntropy( node ) - computeEntropySum( node, attr );
    }

    private double computeEntropySum( Node node, Attribute attr ) throws Exception
    {
        double sum = 0.0;
        for ( int valueIndex = 0; valueIndex < attr.numValues( ); valueIndex++ )
        {
            int count = node.getCount( attr, valueIndex );

            if ( count > 0 )
            {
                double entropy = computeEntropy( node, attr, valueIndex );
                double ratio = ( ( double ) count / ( double ) node.getCount( ) );
                sum += ratio * entropy;
            }
        }
        return sum;
    }

    /**
     * Computes the entropy of a dataset.
     * 
     * @param node the tree node for which entropy is to be computed
     * @return the entropy of the node's class distribution
     * @throws Exception if computation fails
     * @see weka.classifiers.trees.Id3#computeEntropy( Instances )
     */
    private double computeEntropy( Node node ) throws Exception
    {
        double entropy = 0;
        double totalCount = ( double ) node.getCount( );
        for ( int classIndex = 0; classIndex < numClasses; classIndex++ )
        {
            int count = node.getCount( classIndex );

            if ( count > 0 )
            {
                double p = count / totalCount;
                entropy -= p * Utils.log2( p );
            }
        }

        return entropy;
    }

    /**
     * Computes the entropy of the child node created by splitting on the
     * provided attribute and value.
     * 
     * @param node the tree node for which entropy is to be computed
     * @param attribute the attribute to split on before calculating entropy
     * @param valueIndex calculate entropy for the child node corresponding
     *        to this nominal attribute value index
     * @return calculated entropy
     * @throws Exception if computation fails
     */
    private double computeEntropy( Node node, Attribute attribute, int valueIndex ) throws Exception
    {
        double entropy = 0;
        double totalCount = ( double ) node.getCount( attribute, valueIndex );
        for ( int classIndex = 0; classIndex < numClasses; classIndex++ )
        {
            int count = node.getCount( attribute, valueIndex, classIndex );

            if ( count > 0 )
            {
                double p = count / totalCount;
                entropy -= p * Utils.log2( p );
            }
        }

        return entropy;
    }

    /**
     * Calculates the difference in information gain, epsilon, between the 
     * attribute with the best and second best information gain necessary to
     * make a splitting decision based on the current set of observed attributes.
     * As more attributes are gathered, the required difference will decrease.
     * 
     * @param node
     * @return
     */
    // see: vfdt-engine.c:833
    private double calculateHoeffdingBound( Node node )
    {
        int n = node.getCount( );
        double epsilon = Math.sqrt( ( R_squared * ln_inv_delta ) / ( 2 * n ) );
        return epsilon;
    }

    /**
     * @see #getLeafNode(Node, Instance)
     */
    private Node getLeafNode( Instance instance )
    {
        return getLeafNode( root, instance );
    }

    /**
     * Traverses the node tree for the provided instance and returns the leaf node
     * associated with the provided instance.
     * 
     * @param instance the instance to be classified
     * @return the leaf node for the instance
     * @see weka.classifiers.trees.Id3#classifyInstance(Instance)
     */
    private Node getLeafNode( Node node, Instance instance )
    {
        // this is a leaf node, so return this node
        if ( node.getAttribute( ) == null )
        {
            return node;
        }
        // this is an internal node, move to the next child based on the m_Attribute for this node
        else
        {
            int attributeValue = ( int ) instance.value( node.getAttribute( ) );
            Node childNode = node.getSuccessor( attributeValue );
            return getLeafNode( childNode, instance );
        }
    }
}