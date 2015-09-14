package org.transitime.custom.aws;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.avl.AvlClient;
import org.transitime.config.ClassConfigValue;
import org.transitime.config.IntegerConfigValue;
import org.transitime.config.StringConfigValue;
import org.transitime.db.structs.AvlReport;
import org.transitime.modules.Module;
import org.transitime.utils.threading.BoundedExecutor;
import org.transitime.utils.threading.NamedThreadFactory;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;

/**
 * Reads AVL data from AWS SQS topic, deserializes it, and process it
 * following the patter established by AvlJmsClientModule. 
 *
 */
public class AvlSqsClientModule extends Module {
  private static final Logger logger = 
      LoggerFactory.getLogger(AvlSqsClientModule.class);
  
  private final BoundedExecutor _avlClientExecutor;
  private AWSCredentials _credentials;
  private AmazonSQS _sqs;
  private String _url = null;
  private SqsMessageUnmarshaller _messageUnmarshaller;
  private int _messageCount = 0;
  private long _messageStart = System.currentTimeMillis();
  
  private final static int MAX_THREADS = 100;

  private static final int DEFAULT_MESSAGE_LOG_FREQUENCY = 10000;
  
  private static IntegerConfigValue avlQueueSize = 
      new IntegerConfigValue("transitime.avl.jmsQueueSize", 350,
          "How many items to go into the blocking AVL queue "
          + "before need to wait for queue to have space. "
          + "Only for when JMS is used.");

  private static IntegerConfigValue numAvlThreads = 
      new IntegerConfigValue("transitime.avl.jmsNumThreads", 1,
          "How many threads to be used for processing the AVL " +
          "data. For most applications just using a single thread " +
          "is probably sufficient and it makes the logging simpler " +
          "since the messages will not be interleaved. But for " +
          "large systems with lots of vehicles then should use " +
          "multiple threads, such as 3-5 so that more of the cores " +
          "are used. Only for when JMS is used.");
  
  private static IntegerConfigValue messageLogFrequency =
      new IntegerConfigValue("transitime.avl.messageLogFrequency", 
          DEFAULT_MESSAGE_LOG_FREQUENCY, 
          "How often (in count of message) a log message is output " +
          "confirming messages have been received");
  
  private static StringConfigValue avlUrl =
      new StringConfigValue("transitime.avl.sqsUrl", null, "The SQS URL from AWS");
  
  private static ClassConfigValue unmarshallerConfig =
      new ClassConfigValue("transitime.avl.unmarshaller", WmataAvlTypeUnmarshaller.class, 
          "Implementation of SqsMessageUnmarshaller to perform " + 
      "the deserialization of SQS Message objects into AVLReport objects");
  
    public AvlSqsClientModule(String agencyId) throws Exception {
      super(agencyId);
      logger.info("loading AWS SQS credentials from environment");
      _credentials =  new EnvironmentVariableCredentialsProvider().getCredentials();
      connect();

      int maxAVLQueueSize = avlQueueSize.getValue();
      int numberThreads = numAvlThreads.getValue();
      _url = avlUrl.getValue();
      
      logger.info("Starting AvlClient for agencyId={} with "
          + "maxAVLQueueSize={}, numberThreads={} and url={}", agencyId,
          maxAVLQueueSize, numberThreads, _url);

      // Make sure that numberThreads is reasonable
      if (numberThreads < 1) {
        logger.error("Number of threads must be at least 1 but {} was "
            + "specified. Therefore using 1 thread.", numberThreads);
        numberThreads = 1;
      }
      if (numberThreads > MAX_THREADS) {
        logger.error("Number of threads must be no greater than {} but "
            + "{} was specified. Therefore using {} threads.",
            MAX_THREADS, numberThreads, MAX_THREADS);
        numberThreads = MAX_THREADS;
      }

      
      // Create the executor that actually processes the AVL data
      NamedThreadFactory avlClientThreadFactory = new NamedThreadFactory(
          "avlClient");
      Executor executor = Executors.newFixedThreadPool(numberThreads,
          avlClientThreadFactory);
      _avlClientExecutor = new BoundedExecutor(executor, maxAVLQueueSize);
      
      // create an instance of the SQS message unmarshaller
      _messageUnmarshaller = (SqsMessageUnmarshaller) unmarshallerConfig.getValue().newInstance();
    }
    
    
    
    private synchronized void connect() {
      _sqs = new AmazonSQSClient(_credentials);
      Region usEast1 = Region.getRegion(Regions.US_EAST_1);
      _sqs.setRegion(usEast1);
    }

    @Override
    public void run() {
      while (!Thread.interrupted()) {
        try {
          processAVLDataFromSQS();
        } catch (Exception e) {
          logger.error("issue processing data:", e);
        }
      }
    }


    private void processAVLDataFromSQS() {
      while (!Thread.interrupted()) {
        try {
          ReceiveMessageRequest request = new ReceiveMessageRequest(_url);
          List<Message> messages = _sqs.receiveMessage(request).getMessages();
          _messageCount += messages.size();
          
          for (Message message : messages) {
            AvlReport avlReport = null;
            try {
              avlReport = _messageUnmarshaller.deserialize(message);
            } catch (Exception any) {
              logger.error("exception deserializing mesage={}:{}", message, any);
            }
            
            if (avlReport != null) {
              Runnable avlClient = new AvlClient(avlReport);
              _avlClientExecutor.execute(avlClient);
            } else {
              // we could potentially quiet this statement some -- but for now
              // its important we know how many message fail deserialization
              logger.error("unable to deserialize avlReport for message={}", message);
            }
          }
          
          // let SQS know we processed the messages
          if (messages != null && messages.size() > 0) {
            // only acknowledge receipt of the transmission, not of each message
            String messageReceiptHandle = messages.get(0).getReceiptHandle();
            _sqs.deleteMessage(new DeleteMessageRequest(_url, messageReceiptHandle));
            // TOOD -- optionally re-queue in archiver queue
          }
          
        } catch (Exception e) {
          logger.error("issue receiving request", e);
          // just in case, we reconnect
          connect();
        }
        
        // put out a log message to show progress every so often
        if (_messageCount != 0 && _messageCount % messageLogFrequency.getValue() == 0) {
          long delta = (System.currentTimeMillis() - _messageStart)/1000;
          long rate = _messageCount / delta;
          logger.info("received " + _messageCount + " message in " +
              delta + " seconds (" + rate + "/s)");
          _messageStart = System.currentTimeMillis();
          _messageCount = 0;
        }
      }
    }

}
