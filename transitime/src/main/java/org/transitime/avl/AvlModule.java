/* 
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.transitime.avl;



import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Properties;

import javax.jms.JMSException;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.configData.AvlConfig;
import org.transitime.db.structs.AvlReport;
import org.transitime.ipc.jms.JMSWrapper;
import org.transitime.ipc.jms.RestartableMessageProducer;
import org.transitime.modules.Module;


/**
 * Low-level abstract AVL module class that handles the processing
 * of the data. Uses JMS to queue the data if JMS enabled.
 * 
 * @author SkiBu Smith
 * 
 */
public abstract class AvlModule extends Module {
	// For writing the AVL data to the JMS topic
	protected RestartableMessageProducer jmsMsgProducer = null; 

	private static final Logger logger = 
			LoggerFactory.getLogger(AvlModule.class);	

	/********************** Member Functions **************************/

	/**
	 * Constructor
	 * 
	 * @param agencyId
	 */
	protected AvlModule(String agencyId) {
		super(agencyId);		
	}
	
	/**
	 * Initializes JMS if need be. Needs to be done from same thread that
	 * JMS is written to. Otherwise get concurrency error. 
	 */
	private void initializeJmsIfNeedTo() {
		// If JMS already initialized then can return
		if (jmsMsgProducer != null)
			return;
		
		// JMS not already initialized so create the MessageProducer 
		// that the AVL data can be written to
		try {
			String jmsTopicName = AvlJmsClientModule.getTopicName(agencyId);
			JMSWrapper jmsWrapper = JMSWrapper.getJMSWrapper();
			jmsMsgProducer = jmsWrapper.createTopicProducer(jmsTopicName);
		} catch (Exception e) {
			logger.error("Problem when setting up JMSWrapper for the AVL feed", e);			
		}

	}
	
	/**
	 * Processes AVL report read from feed. To be called from the subclass for
	 * each AVL report. Can use JMS or bypass it, depending on how configured.
	 */
	protected void processAvlReport(AvlReport avlReport) {
		if(AvlConfig.sendToBarefoot())
			sendToBarefoot(avlReport);
		if (AvlConfig.shouldUseJms()) {
			processAvlReportUsingJms(avlReport);
		} else {
			processAvlReportWithoutJms(avlReport);
		}
	}
	
	private void sendToBarefoot(AvlReport avlReport) {
		try {
			JSONObject report=new JSONObject();
			
			InetAddress host = InetAddress.getLocalHost();
			
			Properties properties = new Properties();
			
			properties.load(new FileInputStream("/tmp/tracker.properties"));
			
			int port = Integer.parseInt(properties.getProperty("server.port"));
						
	        report.put("id", avlReport.getVehicleId());
	       	        	        
	        SimpleDateFormat df = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ssZ" );
	        
	        report.put("time",df.format(avlReport.getDate()));
	        
	        report.put("point", "POINT("+avlReport.getLon()+" " +avlReport.getLat()+")");
	        
			sendBareFootSample(host, port, report);
			
		} catch (Exception e) {
			logger.error("Problem when sending samples to barefoot.", e);
		}
		
	}
	private void sendBareFootSample(InetAddress host, int port, JSONObject sample)
	            throws Exception {
	        int trials = 120;
	        int timeout = 500;
	        Socket client = null;
	        // TODO Will need to leave socket open.
	        while (client == null || !client.isConnected()) {
	            try {
	                client = new Socket(host, port);
	            } catch (IOException e) {
	                Thread.sleep(timeout);

	                if (trials == 0) {
	                    logger.error(e.getMessage());
	                    client.close();
	                    throw new IOException();
	                } else {
	                    trials -= 1;
	                }
	            }
	        }
	        PrintWriter writer = new PrintWriter(client.getOutputStream());
	        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
	        writer.println(sample.toString());
	        writer.flush();

	        String code = reader.readLine();
	        if(!code.equals("SUCCESS"))
	        {
	        	throw new Exception("Barefoot server did not respond with SUCCESS");
	        }
	}

	/**
	 * Processes entire collection of AVL reports read from feed by calling
	 * processAvlReport() on each one.
	 * 
	 * @param avlReports
	 */
	protected void processAvlReports(Collection<AvlReport> avlReports) {
		for (AvlReport avlReport : avlReports) {
			processAvlReport(avlReport);
		}
	}
	
	/**
	 * Sends the AvlReport object to the JMS topic so that AVL clients can read it.
	 * @param avlReport
	 */
	private void processAvlReportUsingJms(AvlReport avlReport) {
		// Make sure the JMS stuff setup successfully
		initializeJmsIfNeedTo();
		if (jmsMsgProducer == null) {
			logger.error("Cannot write AvlReport to JMS because JMS tools " + 
					"were not initialized successfully.");
			return;
		}
			
		// Send the AVL report to the JMS topic
		try {
			jmsMsgProducer.sendObjectMessage(avlReport);
		} catch (JMSException e) {
			logger.error("Problem sending AvlReport to the JMS topic", e);
		}		
	}

	/**
	 * Instead of writing AVL report to JMS topic this method directly processes
	 * it. By doing this one can bypass the need for a JMS server. Uses a thread
	 * executor so that can both use multiple threads and queue up requests.
	 * This is especially important if getting a dump of AVL data from an AVL
	 * feed hitting the Transitime web server and the AVL data getting then
	 * pushed to the core system in batches.
	 * 
	 * @param avlReport
	 *            The AVL report to be processed
	 */
	private void processAvlReportWithoutJms(AvlReport avlReport) {
		// Use AvlExecutor to actually process the data using a thread executor
		AvlExecutor.getInstance().processAvlReport(avlReport);	
	}
}
