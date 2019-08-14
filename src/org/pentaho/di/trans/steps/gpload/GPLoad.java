/*!
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright (c) 2002-2013 Pentaho Corporation..  All rights reserved.
 */

package org.pentaho.di.trans.steps.gpload;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;

//
// The "designer" notes of the Greenplum bulkloader:
// ----------------------------------------------
//
// - "Enclosed" is used in the loader instead of "optionally enclosed" as optionally
//   encloses kind of destroys the escaping.
// - A Boolean is output as Y and N (as in the text output step e.g.). If people don't
//   like this they can first convert the boolean value to something else before loading
//   it.
// - Filters (besides data and datetime) are not supported as it slows down.
//
// 

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

/**
 * Performs a bulk load to an Greenplum table.
 * 
 * Based on (copied from) Sven Boden's Oracle Bulk Loader step
 * 
 * @author Luke Lonergan, Matt Casters, Sean Flatley
 * @since 28-mar-2008, 17-dec-2010
 */
public class GPLoad extends BaseStep implements StepInterface {
	private static Class<?> PKG = GPLoadMeta.class; // for i18n purposes, needed by Translator2!! $NON-NLS-1$

	private static String INDENT = "    ";
	private static String GPLOAD_YAML_VERSION = "VERSION: 1.0.0.1";
	private static String SINGLE_QUOTE = "'";
	private static String OPEN_BRACKET = "[";
	private static String CLOSE_BRACKET = "]";
	private static String SPACE_PADDED_DASH = " - ";
	private static String COLON = ":";
	private static char DOUBLE_QUOTE = '"';

	Process gploadProcess = null;

	private GPLoadMeta meta;
	protected GPLoadData data;
	private GPLoadDataOutput output = null;

	/*
	 * Local copy of the transformation "preview" property. We only forward the rows
	 * upon previewing, we don't do any of the real stuff.
	 */
	private boolean preview = false;

	//
	// This class continually reads from the stream, and sends it to the log
	// if the logging level is at least basic level.
	//
	private final class StreamLogger extends Thread {
		private InputStream input;
		private String type;

		StreamLogger(InputStream is, String type) {
			this.input = is;
			this.type = type + ">";
		}

		public void run() {
			try {
				final BufferedReader br = new BufferedReader(new InputStreamReader(input));
				String line;
				while ((line = br.readLine()) != null) {
					// Only perform the concatenation if at basic level. Otherwise,
					// this just reads from the stream.
					if (log.isBasic()) {
						logBasic(type + line);
					}
				}
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}

		}

	}

	public GPLoad(StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
			Trans trans) {
		super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
	}

	/**
	 * Get the contents of the control file as specified in the meta object
	 * 
	 * @param meta
	 *            the meta object to model the control file after
	 * 
	 * @return a string containing the control file contents
	 */
	public String getControlFileContents(GPLoadMeta meta, RowMetaInterface rm) throws KettleException {

		String[] tableFields = meta.getFieldTable();
		boolean[] matchColumn = meta.getMatchColumn();
		boolean[] updateColumn = meta.getUpdateColumn();

		// TODO: All this validation could be placed in it's own method,

		// table name validation
		DatabaseMeta databaseMeta = meta.getDatabaseMeta();
		String schemaName = meta.getSchemaName();
		String targetTableName = meta.getTableName();

		// TODO: What is schema name to a GreenPlum database?
		// Testing has been with an empty schema name
		// We will set it to an empty string if it is null
		// If it is not null then we will process what it is
		if (schemaName == null) {
			schemaName = "";
		}

		if (targetTableName == null) {
			throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.TargetTableNameMissing"));
		}
		targetTableName = environmentSubstitute(targetTableName).trim();
		if (Const.isEmpty(targetTableName)) {
			throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.TargetTableNameMissing"));
		}

		// Schema name should be unquoted (gpload yaml parse error)
		schemaName = environmentSubstitute(schemaName);
		if (Const.isEmpty(schemaName)) {
			schemaName = databaseMeta.getPreferredSchemaName();
		}
		if (Const.isEmpty(schemaName)) {
			schemaName = "";
		} else {
			schemaName = "\"" + schemaName + "\".";
		}
		if (meta.islowertables()) {
			targetTableName = schemaName.toLowerCase() + "\"" + targetTableName.toLowerCase()
					+ "\"";
		} else {
			targetTableName = schemaName + "\"" + targetTableName + "\"";
		}

		String loadAction = meta.getLoadAction();

		// match and update column verification
		if (loadAction.equalsIgnoreCase(GPLoadMeta.ACTION_MERGE)
				|| loadAction.equalsIgnoreCase(GPLoadMeta.ACTION_UPDATE)) {

			// throw an exception if we don't have match columns
			if (matchColumn == null) {
				throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.MatchColumnsNeeded"));
			}

			if (!meta.hasMatchColumn()) {
				throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.MatchColumnsNeeded"));
			}

			// throw an exception if we don't have any update columns
			if (updateColumn == null) {
				throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.UpdateColumnsNeeded"));
			}

			if (!meta.hasUpdateColumn()) {
				throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.UpdateColumnsNeeded"));
			}
		}

		// data file validation
		String dataFilename = meta.getDataFile();
		if (!Const.isEmpty(dataFilename)) {
			dataFilename = environmentSubstitute(dataFilename).trim();
		}
		if (Const.isEmpty(dataFilename)) {
			throw new KettleException(BaseMessages.getString(PKG, "GPload.Exception.DataFileMissing"));
		}

		// delimiter validation
		String delimiter = meta.getDelimiter();
		if (!Const.isEmpty(delimiter)) {
			delimiter = environmentSubstitute(delimiter).trim();
		}
		if (Const.isEmpty(delimiter)) {
			throw new KettleException(BaseMessages.getString(PKG, "GPload.Exception.DelimiterMissing"));
		}

		// Now we start building the contents
		StringBuffer contents = new StringBuffer(1000);

		// Source: GP Admin Guide 3.3.6, page 635:
		contents.append(GPLoad.GPLOAD_YAML_VERSION).append(Const.CR);
		contents.append("DATABASE: ");
		contents.append(environmentSubstitute(databaseMeta.getDatabaseName()));
		contents.append(Const.CR);
		contents.append("USER: ").append(environmentSubstitute(databaseMeta.getUsername())).append(Const.CR);
		contents.append("HOST: ").append(environmentSubstitute(databaseMeta.getHostname())).append(Const.CR);
		contents.append("PORT: ").append(environmentSubstitute(databaseMeta.getDatabasePortNumberString()))
				.append(Const.CR);
		contents.append("GPLOAD:").append(Const.CR);
		contents.append(GPLoad.INDENT).append("INPUT: ").append(Const.CR);
		contents.append(GPLoad.INDENT).append("- SOURCE: ").append(Const.CR);

		// Add a LOCAL_HOSTS section
		// We first check to see if the array has any elements
		// if so we proceed with the string building - if not we do not add
		// LOCAL_HOSTNAME section.
		String[] localHosts = meta.getLocalHosts();
		String stringLocalHosts = null;
		if (!Const.isEmpty(localHosts)) {
			StringBuilder sbLocalHosts = new StringBuilder();
			String trimmedAndSubstitutedLocalHost;
			for (String localHost : localHosts) {
				trimmedAndSubstitutedLocalHost = environmentSubstitute(localHost.trim());
				if (!Const.isEmpty(trimmedAndSubstitutedLocalHost)) {
					sbLocalHosts.append(GPLoad.INDENT).append(GPLoad.INDENT).append(GPLoad.SPACE_PADDED_DASH)
							.append(trimmedAndSubstitutedLocalHost).append(Const.CR);
				}
			}
			stringLocalHosts = sbLocalHosts.toString();
			if (!Const.isEmpty(stringLocalHosts)) {
				contents.append(GPLoad.INDENT).append(GPLoad.INDENT).append("LOCAL_HOSTNAME: ").append(Const.CR)
						.append(stringLocalHosts);
			}
		}

		// Add a PORT section if we have a port
		String localhostPort = meta.getLocalhostPort();
		if (!Const.isEmpty(localhostPort)) {
			localhostPort = environmentSubstitute(localhostPort).trim();
			if (!Const.isEmpty(localhostPort)) {
				contents.append(GPLoad.INDENT).append(GPLoad.INDENT).append("PORT: ").append(localhostPort)
						.append(Const.CR);
			}
		}

		// TODO: Stream to a temporary file and then bulk load OR optionally stream to a
		// named pipe (like MySQL bulk loader)
		dataFilename = GPLoad.SINGLE_QUOTE + environmentSubstitute(dataFilename) + GPLoad.SINGLE_QUOTE;
		contents.append(GPLoad.INDENT).append(GPLoad.INDENT).append("FILE: ").append(GPLoad.OPEN_BRACKET)
				.append(dataFilename).append(GPLoad.CLOSE_BRACKET).append(Const.CR);

		String befsql = "drop table " + targetTableName + ";" + "create table " + targetTableName + " (";
		// columns
		if (tableFields.length > 0) {
			contents.append(GPLoad.INDENT).append("- COLUMNS: ").append(Const.CR);

			for (String columnName : tableFields) {
				if (meta.islowertables()) {
					columnName = columnName.toLowerCase();
				}
				if (meta.isautocreatetable()) {
					befsql = befsql + "\"" + columnName + "\" text,";
				}

				contents.append(GPLoad.INDENT).append(GPLoad.INDENT).append(GPLoad.SPACE_PADDED_DASH)
						.append(databaseMeta.quoteField("'\"" + columnName + "\"'")).append(GPLoad.COLON)
						.append(Const.CR);
			}
		}

		// See also page 155 for formatting information & escaping
		// delimiter validation should have been perfomed
		contents.append(GPLoad.INDENT).append("- FORMAT: TEXT").append(Const.CR);
		// ESCAPE
		contents.append(GPLoad.INDENT).append("- ESCAPE: 'OFF'").append(Const.CR);
		// maxlinelength
		contents.append(GPLoad.INDENT).append("- MAX_LINE_LENGTH: ").append(Integer.parseInt(meta.getmaxlinelength()))
				.append(Const.CR);
		contents.append(GPLoad.INDENT).append("- DELIMITER: ").append(GPLoad.SINGLE_QUOTE).append(delimiter)
				.append(GPLoad.SINGLE_QUOTE).append(Const.CR);
		// if ( !Const.isEmpty( meta.getNullAs() ) ) {
		if (!Const.isEmpty(meta.getNullAs())) {
			contents.append(GPLoad.INDENT).append("- NULL_AS: ").append(GPLoad.SINGLE_QUOTE).append(meta.getNullAs())
					.append(GPLoad.SINGLE_QUOTE).append(Const.CR);
		}

		// TODO: implement escape character
		// TODO: test what happens when a single quote is specified- can we specify a
		// single quiote within doubole quotes
		// then?
		String enclosure = meta.getEnclosure();

		// For enclosure we do a null check. !Const.isEmpty will be true if the string
		// is empty.
		// it is ok to have an empty string
		if (enclosure != null) {
			enclosure = environmentSubstitute(meta.getEnclosure());
		} else {
			enclosure = "";
		}
		contents.append(GPLoad.INDENT).append("- QUOTE: ").append(GPLoad.SINGLE_QUOTE).append(enclosure)
				.append(GPLoad.SINGLE_QUOTE).append(Const.CR);
		contents.append(GPLoad.INDENT).append("- HEADER: FALSE").append(Const.CR);

		// ENCODING
		String encoding = meta.getEncoding();
		if (!Const.isEmpty(encoding)) {
			contents.append(GPLoad.INDENT).append("- ENCODING: ").append(encoding).append(Const.CR);
		}

		// Max errors
		String maxErrors = meta.getMaxErrors();
		if (maxErrors == null) {
			maxErrors = GPLoadMeta.MAX_ERRORS_DEFAULT;
		} else {
			maxErrors = environmentSubstitute(maxErrors);
			try {
				if (Integer.valueOf(maxErrors) < 0) {
					throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.MaxErrorsInvalid"));
				}
			} catch (NumberFormatException nfe) {
				throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.MaxErrorsInvalid"));
			}
		}

		contents.append(GPLoad.INDENT).append("- ERROR_LIMIT: ").append(maxErrors).append(Const.CR);

		String errorTableName = meta.getErrorTableName();
		if (!Const.isEmpty(errorTableName)) {
			errorTableName = environmentSubstitute(errorTableName).trim();
			if (!Const.isEmpty(errorTableName)) {
				contents.append(GPLoad.INDENT).append("- ERROR_TABLE: ").append(errorTableName).append(Const.CR);
			}
		}
		String truncate = meta.gettruncate();
		if (!Const.isEmpty(truncate)) {
			contents.append(GPLoad.INDENT).append("PRELOAD:").append(Const.CR);
			contents.append(GPLoad.INDENT).append("- TRUNCATE: ").append(truncate).append(Const.CR);
			contents.append(GPLoad.INDENT).append("- REUSE_TABLES: TRUE ").append(Const.CR);
		}
		// -------------- OUTPUT section

		contents.append(GPLoad.INDENT).append("OUTPUT:").append(Const.CR);

		contents.append(GPLoad.INDENT).append("- TABLE: ").append("'" + targetTableName + "'").append(Const.CR);
		contents.append(GPLoad.INDENT).append("- MODE: ").append(loadAction).append(Const.CR);

		// TODO: MAPPING
		// add auto create table
		if (meta.isautocreatetable()) {
			befsql = befsql.substring(0, befsql.length() - 1) + ");";
		}

		// do the following block if the load action is an update or merge
		if (loadAction.equals(GPLoadMeta.ACTION_UPDATE) || loadAction.equals(GPLoadMeta.ACTION_MERGE)) {

			// if we have match columns then add the specification
			if (meta.hasMatchColumn()) {
				contents.append(GPLoad.INDENT).append("- MATCH_COLUMNS: ").append(Const.CR);

				for (int i = 0; i < matchColumn.length; i++) {
					if (matchColumn[i]) {
						contents.append(GPLoad.INDENT).append(GPLoad.INDENT).append(GPLoad.SPACE_PADDED_DASH)
								.append(databaseMeta.quoteField(tableFields[i])).append(Const.CR);
					}
				}
			}

			// if we have update columns then add the specification
			if (meta.hasUpdateColumn()) {
				contents.append(GPLoad.INDENT).append("- UPDATE_COLUMNS: ").append(Const.CR);

				for (int i = 0; i < updateColumn.length; i++) {
					if (updateColumn[i]) {
						contents.append(GPLoad.INDENT).append(GPLoad.INDENT).append(GPLoad.SPACE_PADDED_DASH)
								.append(databaseMeta.quoteField(tableFields[i])).append(Const.CR);
					}
				}
			}

			// if we have an update condition
			String updateCondition = meta.getUpdateCondition();
			if (!Const.isEmpty(updateCondition)) {

				// replace carriage returns with spaces and trim the whole thing
				updateCondition = updateCondition.replaceAll("[\r\n]", " ").trim();

				// test the contents once again
				// the original contents may have just been linefeed/carriage returns
				if (!Const.isEmpty(updateCondition)) {

					// we'll write out what we have
					contents.append(GPLoad.INDENT).append("- UPDATE_CONDITION: ").append(GPLoad.DOUBLE_QUOTE)
							.append(updateCondition).append(GPLoad.DOUBLE_QUOTE).append(Const.CR);
				}
			}
		}

		return contents.toString();
	}

	/**
	 * Create a control file.
	 *
	 * @param filename
	 * @param meta
	 * @throws KettleException
	 */
	public void createControlFile(GPLoadMeta meta) throws KettleException {
		// 增加自动识别字段
		if (!(meta.getFieldTable().length > 0)) {
			meta.setFieldTable(this.getInputRowMeta().getFieldNames());
		}

		String filename = meta.getControlFile();
		if (Const.isEmpty(filename)) {
			throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.NoControlFileSpecified"));
		} else {
			filename = environmentSubstitute(filename).trim();
			if (Const.isEmpty(filename)) {
				throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.NoControlFileSpecified"));
			}
		}

		File controlFile = new File(filename);
		FileWriter fw = null;

		try {
			controlFile.createNewFile();
			fw = new FileWriter(controlFile);
			fw.write(getControlFileContents(meta, getInputRowMeta()));
		} catch (IOException ex) {
			throw new KettleException(ex.getMessage(), ex);
		} finally {
			try {
				if (fw != null) {
					fw.close();
				}
			} catch (Exception ignored) {
				// Ignore error
			}
		}
	}

	/**
	 * Returns the path to the pathToFile. It should be the same as what was passed
	 * but this method will check the file system to see if the path is valid.
	 * 
	 * @param pathToFile
	 *            Path to the file to verify.
	 * @param exceptionMessage
	 *            The message to use when the path is not provided.
	 * @param checkExistence
	 *            When true the path's existence will be verified.
	 * @return
	 * @throws KettleException
	 */
	private String getPath(String pathToFile, String exceptionMessage, boolean checkExistenceOfFile)
			throws KettleException {

		// Make sure the path is not empty
		if (Const.isEmpty(pathToFile)) {
			throw new KettleException(exceptionMessage);
		}

		// make sure the variable substitution is not empty
		pathToFile = environmentSubstitute(pathToFile).trim();
		if (Const.isEmpty(pathToFile)) {
			throw new KettleException(exceptionMessage);
		}

		FileObject fileObject = KettleVFS.getFileObject(pathToFile, getTransMeta());
		try {
			// we either check the existence of the file
			if (checkExistenceOfFile) {
				if (!fileObject.exists()) {
					throw new KettleException(
							BaseMessages.getString(PKG, "GPLoad.Execption.FileDoesNotExist", pathToFile));
				}
			} else { // if the file does not have to exist, the parent, or source folder, does.
				FileObject parentFolder = fileObject.getParent();
				if (parentFolder.exists()) {
					return KettleVFS.getFilename(fileObject);
				} else {
					throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.DirectoryDoesNotExist",
							parentFolder.getURL().getPath()));
				}

			}

			// if Windows is the OS
			if (Const.getOS().startsWith("Windows")) {
				return addQuotes(pathToFile);
			} else {
				return KettleVFS.getFilename(fileObject);
			}
		} catch (FileSystemException fsex) {
			throw new KettleException(
					BaseMessages.getString(PKG, "GPLoad.Exception.GPLoadCommandBuild", fsex.getMessage()));
		}
	}

	/**
	 * Create the command line for GPLoad depending on the meta information
	 * supplied.
	 * 
	 * @param meta
	 *            The meta data to create the command line from
	 * @param password
	 *            Use the real password or not
	 * 
	 * @return The string to execute.
	 * 
	 * @throws KettleException
	 *             Upon any exception
	 */
	public String createCommandLine(GPLoadMeta meta, boolean password) throws KettleException {

		StringBuffer sbCommandLine = new StringBuffer(300);

		if (Const.getOS().startsWith("Windows")) {
			sbCommandLine.append("cmd /c ");
		}

		// get path to the executable
		sbCommandLine.append(getPath(meta.getGploadPath(),
				BaseMessages.getString(PKG, "GPLoad.Exception.GPLoadPathMisssing"), true));

		// get the path to the control file
		sbCommandLine.append(" -f ");
		sbCommandLine.append(getPath(meta.getControlFile(),
				BaseMessages.getString(PKG, "GPLoad.Exception.ControlFilePathMissing"), false));

		// get the path to the log file, if specified
		String logfile = meta.getLogFile();
		if (!Const.isEmpty(logfile)) {
			sbCommandLine.append(" -l ");
			sbCommandLine.append(getPath(meta.getLogFile(),
					BaseMessages.getString(PKG, "GPLoad.Exception.LogFilePathMissing"), false));
		}
		sbCommandLine.append(" -V ");
		sbCommandLine.append(" --gpfdist_timeout 7200 ");
		return sbCommandLine.toString();
	}

	public boolean execute(GPLoadMeta meta, boolean wait) throws KettleException {
		String commandLine = null;
		Runtime rt = Runtime.getRuntime();
		int gpLoadExitVal = 0;

		try {

			commandLine = createCommandLine(meta, true);
			logBasic("Executing: " + commandLine);

			gploadProcess = rt.exec(commandLine);

			// any error message?
			StreamLogger errorLogger = new StreamLogger(gploadProcess.getErrorStream(), "ERROR");

			// any output?
			StreamLogger outputLogger = new StreamLogger(gploadProcess.getInputStream(), "OUTPUT");

			// kick them off
			errorLogger.start();
			outputLogger.start();

			if (wait) {
				// any error???
				gpLoadExitVal = gploadProcess.waitFor();
				logBasic(BaseMessages.getString(PKG, "GPLoad.Log.ExitValuePsqlPath", "" + gpLoadExitVal));
				if (gpLoadExitVal != 0) {
					throw new KettleException(
							BaseMessages.getString(PKG, "GPLoad.Log.ExitValuePsqlPath", "" + gpLoadExitVal));
				}
			}
		} catch (KettleException ke) {
			throw ke;
		} catch (Exception ex) {
			// Don't throw the message upwards, the message contains the password.
			throw new KettleException("Error while executing \'" + commandLine + "\'. Exit value = " + gpLoadExitVal);
		}

		return true;
	}

	public String[] getsql(GPLoadMeta meta) throws KettleException {
		// 增加自动识别字段
		if (!(meta.getFieldTable().length > 0)) {
			meta.setFieldTable(this.getInputRowMeta().getFieldNames());
		}

		String[] tableFields = meta.getFieldTable();
		String schemaName = meta.getSchemaName();
		String targetTableName = meta.getTableName();
		DatabaseMeta databaseMeta = meta.getDatabaseMeta();
		if (schemaName == null) {
			schemaName = "";
		}

		if (targetTableName == null) {
			throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.TargetTableNameMissing"));
		}
		targetTableName = environmentSubstitute(targetTableName).trim();
		if (Const.isEmpty(targetTableName)) {
			throw new KettleException(BaseMessages.getString(PKG, "GPLoad.Exception.TargetTableNameMissing"));
		}

		// Schema name should be unquoted (gpload yaml parse error)
		schemaName = environmentSubstitute(schemaName);
		if (Const.isEmpty(schemaName)) {
			schemaName = databaseMeta.getPreferredSchemaName();
		}
		if (Const.isEmpty(schemaName)) {
			schemaName = "";
		} else {
			schemaName = "\"" + schemaName + "\".";
		}

		targetTableName = schemaName + "\"" + databaseMeta.quoteField(targetTableName) + "\"";
		String[] sqls = new String[2];
		sqls[0] = "drop table " + targetTableName;
		String befsql = "create table " + targetTableName + " (";
		if (tableFields.length > 0) {

			for (String columnName : tableFields) {
				befsql = befsql + "\"" + columnName + "\" text,";
			}
		}
		if (meta.islowertables()) {
			befsql = befsql.toLowerCase();
		}
		befsql = befsql.substring(0, befsql.length() - 1) + ");";
		// meta, getInputRowMeta()
		sqls[1] = befsql;
		return sqls;

	}

	private void closeOutput() throws Exception {
		if (data.fifoStream != null) {
			// Close the fifo file...
			//
			data.fifoStream.close();
			data.fifoStream = null;
		}
		if (data.gploadexec != null) {

			data.gploadexec.join(3000);
			Gploadexec gploadexecs = data.gploadexec;
			data.gploadexec = null;
			gploadexecs.checkExcn();
		}
	}

	private SimpleDateFormat sdfDate = null;
	private SimpleDateFormat sdfDateTime = null;

	private int[] fieldNumbers = null;
	private String enclosure = null;
	private String delimiter = null;
	
	
	
	private void setparam(RowMetaInterface mi)throws KettleException
	{

		enclosure = meta.getEnclosure();
		if (enclosure == null) {
			enclosure = "";
		} else {
			enclosure = environmentSubstitute(enclosure);
		}

		delimiter = meta.getDelimiter();
		if (delimiter == null) {
			throw new KettleException(BaseMessages.getString(PKG, "GPload.Exception.DelimiterMissing"));
		} else {
			delimiter = environmentSubstitute(delimiter);
			if (Const.isEmpty(delimiter)) {
				throw new KettleException(BaseMessages.getString(PKG, "GPload.Exception.DelimiterMissing"));
			}
		}
		// 增加自动识别字段
		if (!(meta.getFieldStream().length > 0)) {
			meta.setFieldStream(mi.getFieldNames());
		}

		// Setup up the fields we need to take for each of the rows
		// as this speeds up processing.
		fieldNumbers = new int[meta.getFieldStream().length];
		for (int i = 0; i < fieldNumbers.length; i++) {
			fieldNumbers[i] = mi.indexOfValue(meta.getFieldStream()[i]);
			if (fieldNumbers[i] < 0) {
				throw new KettleException(BaseMessages.getString(PKG, "GPLoadDataOutput.Exception.FieldNotFound",
						meta.getFieldStream()[i]));
			}
		}

		sdfDate = new SimpleDateFormat("yyyy-MM-dd");
		sdfDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
	}
	

	public void writeLinefifo(RowMetaInterface mi, Object[] row) throws KettleException {
		try {
			// Write the data to the output
			enclosure = meta.getEnclosure();
			if (enclosure == null) {
				enclosure = "";
			} else {
				enclosure = environmentSubstitute(enclosure);
			}
			delimiter = meta.getDelimiter();
			if (delimiter == null) {
				throw new KettleException(BaseMessages.getString(PKG, "GPload.Exception.DelimiterMissing"));
			} else {
				delimiter = environmentSubstitute(delimiter);
				if (Const.isEmpty(delimiter)) {
					throw new KettleException(BaseMessages.getString(PKG, "GPload.Exception.DelimiterMissing"));
				}
			}

			ValueMetaInterface v = null;
			int number = 0;
			String endocing = meta.getwtencod();
			for (int i = 0; i < fieldNumbers.length; i++) {
				// TODO: variable substitution
				if (i != 0) {
					data.fifoStream.write(delimiter.getBytes());
				}
				number = fieldNumbers[i];
				v = mi.getValueMeta(number);
				if (row[number] == null) {
					// TODO (SB): special check for null in case of Strings.
					data.fifoStream.write(enclosure.getBytes());
					data.fifoStream.write(enclosure.getBytes());
				} else {
					switch (v.getType()) {
					case ValueMetaInterface.TYPE_STRING:
						String s = mi.getString(row, number);
						// 替换特殊分割字符
						// if ( s.indexOf( enclosure ) !=-1 ) {
						// s = createEscapedString( s, delimiter );
						// }
						if (s != null) {
							s = s.replace("\r", "").replace("\n", "").replace(delimiter, meta.getwtreplace()).replace("\t", "")
									.replaceAll("\0x00", "").replaceAll("\u0000", "");
						}
						// String s1=s.replace("\r", "");
						// String s2=s1.replace("\n", "");
						// String s3=s2.replace("\t", "");
						// String s4 =s3.replace(delimiter, "gennlife");
						data.fifoStream.write(enclosure.getBytes());
						data.fifoStream.write(s.getBytes());
						data.fifoStream.write(enclosure.getBytes());

						break;
					case ValueMetaInterface.TYPE_INTEGER:
						Long l = mi.getInteger(row, number);
						if (meta.getEncloseNumbers()) {
							data.fifoStream.write(enclosure.getBytes());
							data.fifoStream.write(String.valueOf(1).getBytes());
							data.fifoStream.write(enclosure.getBytes());
						} else {
							data.fifoStream.write(String.valueOf(1).getBytes());
						}
						break;
					case ValueMetaInterface.TYPE_NUMBER:
						Double d = mi.getNumber(row, number);
						if (meta.getEncloseNumbers()) {

							data.fifoStream.write(enclosure.getBytes());
							data.fifoStream.write(String.valueOf(d).getBytes());
							data.fifoStream.write(enclosure.getBytes());
						} else {
							data.fifoStream.write(String.valueOf(d).getBytes());
						}
						break;
					case ValueMetaInterface.TYPE_BIGNUMBER:
						BigDecimal bd = mi.getBigNumber(row, number);
						if (meta.getEncloseNumbers()) {
							data.fifoStream.write(enclosure.getBytes());
							data.fifoStream.write(String.valueOf(bd).getBytes());
							data.fifoStream.write(enclosure.getBytes());
						} else {
							data.fifoStream.write(String.valueOf(bd).getBytes());
						}
						break;
					case ValueMetaInterface.TYPE_DATE:
						Date dt = mi.getDate(row, number);
						data.fifoStream.write(enclosure.getBytes());
						data.fifoStream.write(sdfDate.format(dt).getBytes());
						data.fifoStream.write(enclosure.getBytes());
						break;
					case ValueMetaInterface.TYPE_BOOLEAN:
						Boolean b = mi.getBoolean(row, number);
						data.fifoStream.write(enclosure.getBytes());
						if (b.booleanValue()) {
							data.fifoStream.write("Y".getBytes());
						} else {
							data.fifoStream.write("N".getBytes());
						}
						data.fifoStream.write(enclosure.getBytes());
						break;
					case ValueMetaInterface.TYPE_BINARY:
						byte[] byt = mi.getBinary(row, number);
						// String string="";
						// try {
						// string=new String(byt,endocing);}
						// catch (Exception e) {
						// TODO: handle exception
						// }
						// string=string.replace("\r", "").replace("\n", "").replace(delimiter,
						// "gennlife").replace("\t","").replaceAll("\0x00", "").replaceAll("\u0000",
						// "");
						String string = output.bytesToHexString(byt);
						data.fifoStream.write(enclosure.getBytes());
						data.fifoStream.write(string.getBytes());
						data.fifoStream.write(enclosure.getBytes());
						break;
					case ValueMetaInterface.TYPE_TIMESTAMP:
						Date time = mi.getDate(row, number);
						data.fifoStream.write(enclosure.getBytes());
						data.fifoStream.write(sdfDateTime.format(time).getBytes());
						data.fifoStream.write(enclosure.getBytes());
						break;
					default:
						throw new KettleException(BaseMessages.getString(PKG,
								"GPLoadDataOutput.Exception.TypeNotSupported", v.getType()));
					}
				}
			}
			data.fifoStream.write(Const.CR.getBytes());
			data.fifoStream.flush();
		} catch (IOException e) {
			throw new KettleException(e.getMessage());
		}
	}

	
	
	
	
	public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException {
		meta = (GPLoadMeta) smi;
		data = (GPLoadData) sdi;

		try {

			Object[] r = getRow(); // Get row from input rowset & set row busy!
			// no more input to be expected...
			if (r == null) {
				setOutputDone();

				if (!preview) {
					if (output != null) {
						// Close the output
						try {
							if (meta.isfifo()) {
								this.closeOutput();
							} else {
								output.close();
							}
						} catch (Exception e) {
							throw new KettleException("Error while closing output", e);
						}

						output = null;
					}

					String loadMethod = meta.getLoadMethod();

					// if it specified that we are to load at the end of processing
					if (GPLoadMeta.METHOD_AUTO_END.equals(loadMethod)) {

						// if we actually wrote at least one row
						if (getLinesOutput() > 0) {

							// we do this
							if (!meta.isfifo()) {
								createControlFile(meta);
								execute(meta, true);
							}
						} else {
							// we don't create a control file and execute
							logBasic(BaseMessages.getString(PKG, "GPLoad.Info.NoRowsWritten"));
						}
					} else if (GPLoadMeta.METHOD_MANUAL.equals(loadMethod)) {

						// we create the control file but do not execute
						if (!meta.isfifo()) {
							createControlFile(meta);
						}
						logBasic(BaseMessages.getString(PKG, "GPLoad.Info.MethodManual"));
					} else {
						throw new KettleException(
								BaseMessages.getString(PKG, "GPload.Execption.UnhandledLoadMethod", loadMethod));
					}
				}
				return false;
			}

			if (!preview) {
				if (first) {
					first = false;
					if (meta.isautocreatetable()) {
						String sql[] = getsql(meta);
						for (String string : sql) {
							try {
								Result res = data.db.execStatement(string);
							} catch (Exception e) {
								logBasic(e.getMessage());
							}
						}
						if (!data.db.isAutoCommit()) {
							data.db.commit();
						}

					}

					output = new GPLoadDataOutput(this, meta, log.getLogLevel());
					setparam(getInputRowMeta());

					// if ( GPLoadMeta.METHOD_AUTO_CONCURRENT.equals(meta.getLoadMethod()) )
					// {
					// execute(meta, false);
					// }
					output.open(this, gploadProcess);
					String loadMethod = meta.getLoadMethod();

					// if it specified that we are to load at the end of processing
					if (GPLoadMeta.METHOD_AUTO_END.equals(loadMethod)) {

						// if we actually wrote at least one row
						// we do this
						if (meta.isfifo()) {
							String commline = createCommandLine(meta, true);
							data.gploadexec = new Gploadexec(commline, true);
							createControlFile(meta);
							if (!Const.isWindows()) {
								try {
									log.logBasic("Opening fifo " + output.getdatafile() + " for writing.");
									OpenFifo openFifo = new OpenFifo(output.getdatafile(),268435456);
									openFifo.start();
									data.gploadexec.start();
									while (true) {
										openFifo.join(200);
										if (openFifo.getState() == Thread.State.TERMINATED) {
											break;
										}
										try {
											
											 data.gploadexec.checkExcn();
										} catch (Exception e) {
											// We need to open a stream to the fifo to unblock the fifo writer
											// that was waiting for the sqlRunner that now isn't running
											new BufferedInputStream(new FileInputStream(output.getdatafile())).close();
											openFifo.join();
											logError("Make sure user has been granted the FILE privilege.");
											logError("");
											throw e;
										}
										try {
											 openFifo.checkExcn();
										} catch (Exception e) {
											throw e;
										}
									}
									data.fifoStream = openFifo.getFifoStream();
								} catch (Exception e) {
									logError(e.getMessage());
									throw new KettleException(BaseMessages.getString(PKG, e.getMessage()));
									// TODO: handle exception
								}
							}
						}

					} else if (GPLoadMeta.METHOD_MANUAL.equals(loadMethod)) {
						// we create the control file but do not execute
						if (meta.isfifo()) {
							createControlFile(meta);
							throw new KettleException(BaseMessages.getString(PKG, "can not use fifo in manual"));
						} 
						logBasic(BaseMessages.getString(PKG, "GPLoad.Info.MethodManual"));
					} else {
						throw new KettleException(
								BaseMessages.getString(PKG, "GPload.Execption.UnhandledLoadMethod", loadMethod));
					}
				}
				if (meta.isfifo()) {
					writeLinefifo(getInputRowMeta(), r);
				} else {
					output.writeLine(getInputRowMeta(), r);
				}
			}
			putRow(getInputRowMeta(), r);
			incrementLinesOutput();

		} catch (KettleException e) {
			logError(BaseMessages.getString(PKG, "GPLoad.Log.ErrorInStep") + e.getMessage());
			setErrors(1);
			stopAll();
			setOutputDone(); // signal end to receiver(s)
			return false;
		}

		return true;
	}

	public boolean init(StepMetaInterface smi, StepDataInterface sdi) {
		meta = (GPLoadMeta) smi;
		data = (GPLoadData) sdi;

		Trans trans = getTrans();
		preview = trans.isPreview();

		if (super.init(smi, sdi)) {

			data.db = new Database(this, meta.getDatabaseMeta());
			data.db.shareVariablesWith(this);
			try {
				if (getTransMeta().isUsingUniqueConnections()) {
					synchronized (getTrans()) {
						data.db.connect(getTrans().getTransactionId(), getPartitionID());
					}
				} else {
					data.db.connect(getPartitionID());
				}

			} catch (Exception e) {
				// TODO: handle exception
			}

			return true;
		}
		return false;
	}

	public void dispose(StepMetaInterface smi, StepDataInterface sdi) {
		meta = (GPLoadMeta) smi;
		data = (GPLoadData) sdi;

		super.dispose(smi, sdi);

		if (!preview && meta.isEraseFiles()) {
			// Erase the created cfg/dat files if requested. We don't erase
			// the rest of the files because it would be "stupid" to erase them
			// right after creation. If you don't want them, don't fill them in.
			FileObject fileObject = null;

			String method = meta.getLoadMethod();
			if (GPLoadMeta.METHOD_AUTO_END.equals(method)) {
				if (meta.getControlFile() != null) {
					try {
						fileObject = KettleVFS.getFileObject(environmentSubstitute(meta.getControlFile()),
								getTransMeta());
						fileObject.delete();
						fileObject.close();
					} catch (Exception ex) {
						logError("Error deleting control file \'" + KettleVFS.getFilename(fileObject) + "\': "
								+ ex.getMessage());
					}
				}
			}

			if (GPLoadMeta.METHOD_AUTO_END.equals(method)) {
				// In concurrent mode the data is written to the control file.
				if (meta.getDataFile() != null) {
					try {
						fileObject = KettleVFS.getFileObject(environmentSubstitute(meta.getDataFile()), getTransMeta());
						fileObject.delete();
						fileObject.close();
					} catch (Exception ex) {
						logError("Error deleting data file \'" + KettleVFS.getFilename(fileObject) + "\': "
								+ ex.getMessage(), ex);
					}
				}
			}

			if (GPLoadMeta.METHOD_MANUAL.equals(method)) {
				logBasic("Deletion of files is not compatible with \'manual load method\'");
			}
		}
	}

	/**
	 * Adds quotes to the passed string if the OS is Windows and there is at least
	 * one space .
	 * 
	 * @param string
	 * @return
	 */
	private String addQuotes(String string) {
		if (Const.getOS().startsWith("Windows") && string.indexOf(" ") != -1) {
			string = "\"" + string + "\"";
		}
		return string;
	}

	static class Gploadexec extends Thread {
		private String commandLine;
		private boolean waite;
		private Exception ex;

		public Gploadexec(String commandLinea, boolean waitea) {
			this.commandLine = commandLinea;
			this.waite = waitea;
			// TODO Auto-generated constructor stub
		}

		public void run() {
			try {
				Runtime rt = Runtime.getRuntime();
				int gpLoadExitVal = 0;
				try {

					Process gploadProcess = rt.exec(commandLine);

					if (waite) {
						// any error???
						gpLoadExitVal = gploadProcess.waitFor();
						if (gpLoadExitVal != 0) {
							throw new KettleException(
									BaseMessages.getString(PKG, "GPLoad.Log.ExitValuePsqlPath", "" + gpLoadExitVal));
						}
					}
				} catch (KettleException ke) {
					throw ke;
				} catch (Exception ex) {
					// Don't throw the message upwards, the message contains the password.
					throw new KettleException(
							"Error while executing \'" + commandLine + "\'. Exit value = " + gpLoadExitVal);
				}
			} catch (Exception ex) {
				System.out.print(ex.getMessage());
				this.ex = ex;
			}
		}

		void checkExcn() throws Exception {
			// This is called from the main thread context to rethrow any saved
			// excn.
			if (ex != null) {
				throw ex;
			}
		}
	}

	 static class OpenFifo extends Thread {
		    private BufferedOutputStream fifoStream = null;
		    private Exception ex;
		    private String fifoName;
		    private int size;

		    OpenFifo( String fifoName, int size ) {
		      this.fifoName = fifoName;
		      this.size = size;
		    }

		    public void run() {
		      try {
		        fifoStream = new BufferedOutputStream( new FileOutputStream( OpenFifo.this.fifoName ), this.size );
		      } catch ( Exception ex ) {
		        this.ex = ex;
		      }
		    }

		    void checkExcn() throws Exception {
		      // This is called from the main thread context to rethrow any saved
		      // excn.
		      if ( ex != null ) {
		        throw ex;
		      }
		    }

		    BufferedOutputStream getFifoStream() {
		      return fifoStream;
		    }
		  }
}
