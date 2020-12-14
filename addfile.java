package edu.handong.csee.isel.online;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;

import edu.handong.csee.isel.data.ExtractData;

public class OnlineMain {
	String dataPath;
	String BICpath;
	boolean verbose;
	boolean help;
	static BaseSetting baseSet;
//상수로 패치 사이즈 수에 따라 기존의 패치 .... enum 
	private final static String firstcommitTimePatternStr = "'(\\d\\d\\d\\d-\\d\\d-\\d\\d\\s\\d\\d:\\d\\d:\\d\\d)'";
	private final static Pattern firstcommitTimePattern = Pattern.compile(firstcommitTimePatternStr);

	private final static String firstKeyPatternStr = "@attribute\\sKey\\s\\{([^,]+)";
	private final static Pattern firstKeyPattern = Pattern.compile(firstKeyPatternStr);

	private final static String defaultLabelPatternStr = "@attribute @@class@@ \\{\\w+,(\\w+)\\}";
	private final static Pattern defaultLabelPattern = Pattern.compile(defaultLabelPatternStr);


	public static void main(String[] args) throws Exception {
		OnlineMain main = new OnlineMain();
		baseSet = new BaseSetting();
		main.run(args);
	}

	private void run(String[] args) throws Exception {
		Options options = createOptions();

		if(parseOptions(options, args)){
			if (help){
				printHelp(options);
				return;
			}
			baseSet.setDataPath(dataPath);
			System.out.println(baseSet.OutputPath());
			System.out.println("Project Name : " + baseSet.ProjectName());
			System.out.println("Reference Path : " + baseSet.ReferenceFolderPath());

			if(baseSet.StartDate() == null) {
				System.out.println("Auto Start & End Date");
			}else {
				System.out.println("Given Start & End Date");
			}

			if((baseSet.UpdateDays() == 0) && (baseSet.GapDays() == 0)) {
				System.out.println("Auto Update & Gap Days");
			}else if(!(baseSet.UpdateDays() == 0) && (baseSet.GapDays() == 0)) {
				System.out.println("Given Update Days, Auto Gap Days");
			}else if((baseSet.UpdateDays() == 0) && !(baseSet.GapDays() == 0)) {
				System.out.println("Given Gap Days, Auto Update Days");
			}else {
				System.out.println("Given Update & Gap Days");
			}
			
			//make online arff file
			ExtractData.main(extratOnlineargs(dataPath,baseSet.referenceFolderPath));
			String OnlineMetricArffPath = baseSet.referenceFolderPath+File.separator+baseSet.projectName+"-online.arff";
			System.out.println(OnlineMetricArffPath);
			//mk result directory
			File OnlineDir = new File(baseSet.OutputPath() +File.separator+baseSet.ProjectName()+File.separator);
			String directoryPath = OnlineDir.getAbsolutePath();
			OnlineDir.mkdir();

			//read BIC file and calculate Average Bug fix time
			Reader in = new FileReader(BICpath);
			Iterable<CSVRecord> records = CSVFormat.RFC4180.withHeader().parse(in);
			int numOfBIC = 0;
			int calDateDays = 0;
			for (CSVRecord record : records) {
				String BICtime = record.get("BIDate");
				String BFCtime = record.get("FixDate");
				calDateDays = calDateDays + calDateBetweenAandB(BICtime,BFCtime);
				numOfBIC++;
			}
			baseSet.setAverageBugFixingTimeDays(calDateDays/numOfBIC);
			baseSet.setEndGapDays(baseSet.AverageBugFixingTimeDays()); //set end gap

			//(1) read arff file
			ArrayList<String> attributeLineList = new ArrayList<>();
			ArrayList<String> dataLineList = new ArrayList<>();

			String content = FileUtils.readFileToString(new File(OnlineMetricArffPath), "UTF-8");
			String[] lines = content.split("\n");

			String firstAttrCommitTime = null;
			int indexOfCommitTime = 0;
			String firstAttrKey = null;
			int indexOfKey = 0;
			String firstAttrLabel = null;
			String defaultLabel = null;

			boolean dataPart = false;
			for (String line : lines) {
				if (dataPart) {
					dataLineList.add(line);
					continue;

				}else if(!dataPart){
					attributeLineList.add(line);
					if(line.startsWith("@attribute @@class@@")) {
						Matcher m = defaultLabelPattern.matcher(line);
						m.find();
						defaultLabel = m.group(1);
						firstAttrLabel = "{0 "+ m.group(1)+",";
					}
					if(line.startsWith("@attribute meta_data-commitTime")) {
						Matcher m = firstcommitTimePattern.matcher(line);
						m.find();
						firstAttrCommitTime = m.group(1);
						indexOfCommitTime = attributeLineList.size()-3;
					}
					if(line.startsWith("@attribute Key {")) {
						Matcher m = firstKeyPattern.matcher(line);
						m.find();
						firstAttrKey = m.group(1);
						indexOfKey = attributeLineList.size()-3;
					}
					if (line.startsWith("@data")) {
						dataPart = true;
					}
				}
			}
			if(dataLineList.size() < 10) {
				System.out.println("Wrong arff file");
				System.exit(0);
			}
			TreeMap<String,TreeSet<String>> commitTime_commitHash = new TreeMap<>();
			HashMap<String,ArrayList<String>> commitHash_data = new HashMap<>();
			HashMap<String,ArrayList<Boolean>> commitHash_isBuggy = new HashMap<>();

			for(String line : dataLineList) {
				String commitTime = parsingCommitTime(line,firstAttrCommitTime,indexOfCommitTime);
				String commitHash = parsingCommitHash(line,firstAttrKey,indexOfKey);
				String aData = parsingDataLine(line,indexOfCommitTime,indexOfKey);
				boolean isBuggy  = parsingBugCleanLabel(line,firstAttrLabel,defaultLabel);

				//put2commitTime_commitHash
				TreeSet<String> commitHashs;
				if(commitTime_commitHash.containsKey(commitTime)) {
					commitHashs = commitTime_commitHash.get(commitTime);
					//					if(commitHashs.contains(commitHash)) System.out.println("222");
					commitHashs.add(commitHash);
				}else {
					commitHashs = new TreeSet<>();
					commitHashs.add(commitHash);
					commitTime_commitHash.put(commitTime, commitHashs);
				}

				//put2commitHash_data
				ArrayList<String> data;
				if(commitHash_data.containsKey(commitHash)) {
					data = commitHash_data.get(commitHash);
					data.add(aData);
				}else {
					data = new ArrayList<>();
					data.add(aData);
					commitHash_data.put(commitHash, data);
				}

				//put2commitHash_isBuggy
				ArrayList<Boolean> buggys;
				if(commitHash_isBuggy.containsKey(commitHash)) {
					buggys = commitHash_isBuggy.get(commitHash);
					buggys.add(isBuggy);
				}else {
					buggys = new ArrayList<>();
					buggys.add(isBuggy);
					commitHash_isBuggy.put(commitHash, buggys);
				}
			}

			//set total first, last commit time
			baseSet.setFirstCommitTimeStr(commitTime_commitHash.firstKey());
			baseSet.setLastCommitTimeStr(commitTime_commitHash.lastKey());
			System.out.println(baseSet.FirstCommitTimeStr());
			System.out.println(baseSet.LastCommitTimeStr());
			System.out.println();

			//total change
			int maxChange = 0;
			baseSet.setTotalChange(commitHash_data.size());
			if(baseSet.TotalChange() < 2000) {
				System.out.println("The num of total change is less than 2000.\nTotal Change : "+commitHash_data.size()+"\nBye!");
				System.exit(0);
			}else if(baseSet.TotalChange() >= 2000 && baseSet.TotalChange() < 5000) {
				maxChange = 2000;
			}else if(baseSet.TotalChange() >= 5000 && baseSet.TotalChange() < 10000) {
				maxChange = 5000;
			}else if(baseSet.TotalChange() >= 10000) {
				maxChange = 10000;
			}

			//total experiment change
			TreeMap<String,TreeSet<String>> commitTime_commitHash_experimental = null;
			int defaultStartGap = 365 * 3; // startGap default : 3 years 
			if(baseSet.StartDate() == null) {
				while(true) {
					System.out.println(defaultStartGap);
					commitTime_commitHash_experimental = new TreeMap<>();

					//set startGapDate str
					String startGapDate = addDate(baseSet.FirstCommitTimeStr(),defaultStartGap);
					baseSet.setStartDate(findNearDate(startGapDate,commitTime_commitHash,"r"));

					//set endGapDate str
					String endGapDate = addDate(baseSet.LastCommitTimeStr(),-baseSet.EndGapDays());
					//조건문 추가, 만약 last commit time str에서 average bug fixing time을 뺀 값이 음수일 경우? - 개발 전체 기간이 평균fixing time보다 작아야 일어나는 현상이라 상관 안해도 될듯 
					//조건문 추가, 만약 start date가 end date보다 클 경우 start date를 낮춘다 3year -> 2year
					baseSet.setEndDate(findNearDate(endGapDate,commitTime_commitHash,"l"));

					//					System.out.println("real str date : "+baseSet.getStartGapStr());
					//					System.out.println("real end date : "+baseSet.getEndGapStr());
					//					System.out.println();

					//count total experiment change
					for(String commitTime : commitTime_commitHash.keySet()) {
						if(!(baseSet.StartDate().compareTo(commitTime)<=0 && commitTime.compareTo(baseSet.EndDate())<=0))
							continue;
						TreeSet<String> commitHash = commitTime_commitHash.get(commitTime);
						baseSet.setTotalExperimentalCommit(commitHash.size());
						commitTime_commitHash_experimental.put(commitTime, commitHash);
					}

					System.out.println("ExpCh : "+baseSet.TotalExperimentalCommit());
					if((baseSet.TotalExperimentalCommit() > maxChange) ) 
						break;

					defaultStartGap -= 30;
					if(defaultStartGap < 0) defaultStartGap = 0;
					baseSet.resetTotalExperimentalCommit();
				}
			}else {
				commitTime_commitHash_experimental = new TreeMap<>();

				for(String commitTime : commitTime_commitHash.keySet()) {
					if(!(baseSet.StartDate().compareTo(commitTime)<=0 && commitTime.compareTo(baseSet.EndDate())<=0))
						continue;
					//TODO 전체 커밋 해쉬가 10000개 안되는 경우에는...? - 일단은 첫번째 설정으로 돌도록 함 
					TreeSet<String> commitHash = commitTime_commitHash.get(commitTime);
					baseSet.setTotalExperimentalCommit(commitHash.size());
					commitTime_commitHash_experimental.put(commitTime, commitHash);
				}

				System.out.println("ExpCh : "+baseSet.TotalExperimentalCommit());

				//not use defaultStartGap
			}

			System.out.println();
			System.out.println("real str date : "+baseSet.StartDate());
			System.out.println("real end date : "+baseSet.EndDate());
			System.out.println();
			//			System.exit(0);
			//set total buggy rate
			float totalBugRatio = calBuggyRatio(baseSet.StartDate(),baseSet.EndDate(),commitHash_isBuggy,commitTime_commitHash_experimental);
			baseSet.setTotalBuggyRatio(totalBugRatio);
			System.out.println("Total Bug Ratio : " + totalBugRatio*100 +"%");

			//cal training set 1000 -> 1100 -> 1200
			TreeMap<Float,ArrayList<String>> tr_bugRatio_endDate_numOfCommit = new TreeMap<>(Collections.reverseOrder()); //bug ratio reverse


			for(int i = 1000; ; i += 100) {
				ArrayList<String> endDate_numOfCommit = calEndDateNumOfCommit(baseSet.StartDate(),i,commitTime_commitHash_experimental);
				float bugRatio = calBuggyRatio(baseSet.StartDate(),endDate_numOfCommit.get(0),commitHash_isBuggy,commitTime_commitHash_experimental);
				endDate_numOfCommit.add(2,Integer.toString(i));
				//endDate_numOfCommit : index 0 : endDate / index 1 : real number of Commit / index 2 : input number of Commit
				tr_bugRatio_endDate_numOfCommit.put(bugRatio,endDate_numOfCommit);
				if((i >= 2000) && !(tr_bugRatio_endDate_numOfCommit.firstKey() == 0)) break;
			}

			System.out.println();
			float tra_bugRatio = tr_bugRatio_endDate_numOfCommit.firstKey();
			ArrayList<String> endDate_numOfCommit =  tr_bugRatio_endDate_numOfCommit.get(tra_bugRatio);
			String tr_endDate = endDate_numOfCommit.get(0);
			String tr_numOfCommit = endDate_numOfCommit.get(1);
			int default_Tr_numOfCommit = Integer.parseInt(endDate_numOfCommit.get(2));
			baseSet.setDefault_Tr_size(default_Tr_numOfCommit);

			System.out.println("TrainingSet Bug Ratio : " + tra_bugRatio*100 +"%");
			System.out.println("TrainingSet EndDate : " + tr_endDate);
			System.out.println("TrainingSet numOfCommit : " + tr_numOfCommit);
			System.out.println("TrainingSet default NumOfCommit : " + default_Tr_numOfCommit);
			System.out.println();

			//Cal gap 1 -> 2 ... -> 5 and updateDays 30 -> 40 -> ... -> 100
			TreeMap<Float, ArrayList<Integer>> MV_gapDays_updateDays = new TreeMap<>(Collections.reverseOrder());
			int gapDays = 1;
			int updateDays = 30;

			while(true) {
				//				System.out.println("===============================================");
				String gap_startDate = tr_endDate;
				String gap_endDate = addMonth(gap_startDate,gapDays);
				//				System.out.println(gap_endDate);

				//				System.out.println("Gap startDate : " + gap_startDate);
				//				System.out.println("Gap endDate : " + gap_endDate);
				//				System.out.println("Gap Month : " + gapDays);
				//				System.out.println();

				TreeMap<Float, Integer> MV_updateDays = new TreeMap<>(Collections.reverseOrder());

				if(!(baseSet.UpdateDays() > 0 )) {
					for(updateDays = 30; updateDays <= 100; updateDays += 10) {
						String fromDate = gap_endDate;
						//						System.out.println("-------------------------------------------------------");
						//						System.out.println("updateDays : " + updateDays);

						int run = 0;
						ArrayList<Float> bugRatios = new ArrayList<>();
						float meanBugRatio = 0;
						float varianceBugRatio = 0;
						float sum = 0;

						while(true) {
							String toDate = addDate(fromDate,updateDays);
							float bugRatio = calBuggyRatio(fromDate,toDate,commitHash_isBuggy,commitTime_commitHash_experimental);
							//							System.out.println(bugRatio);

							if((bugRatio != 200) && (bugRatio != 0)) {
								run++;
								sum += bugRatio;
								bugRatios.add(bugRatio);
							}

							String before  = fromDate;

							fromDate = toDate;

							if(baseSet.EndDate().compareTo(fromDate)<=0) {
								//								System.out.println("run : " + run);
								//								System.out.println("fromDate : " + before);
								//								System.out.println("toDate(new end) : " + fromDate); //이제 real end date가 된다. 
								//								System.out.println(baseSet.EndDate()); //end data가 늘어남 
								break;
							}

						} //while (cal each update day)

						//cal mean and variance of Bug ratio
						meanBugRatio = sum / (float) bugRatios.size();
						for(float ratio : bugRatios) {
							varianceBugRatio += Math.pow((ratio - meanBugRatio), 2);
						}
						varianceBugRatio = varianceBugRatio / (float) bugRatios.size();

						MV_updateDays.put((meanBugRatio - varianceBugRatio), updateDays);

						//						System.out.println();
						//						System.out.println("mean bug ratio : " + meanBugRatio);
						//						System.out.println("variance bug ratio : " + varianceBugRatio);
						//						System.out.println("run : " + bugRatios.size());
						//						System.out.println();
					} //for (cal best update days)
					//pick best 
					ArrayList<Integer> gap_update = new ArrayList<Integer>();
					//					System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%");
					//					System.out.println("mean - variance : " + MV_updateDays.firstKey());
					//					System.out.println("updateDays : " + MV_updateDays.get(MV_updateDays.firstKey()));

					gap_update.add(0,gapDays);
					gap_update.add(1,MV_updateDays.get(MV_updateDays.firstKey()));

					MV_gapDays_updateDays.put(MV_updateDays.firstKey(), gap_update);

					MV_updateDays.clear();
				}//if

				gapDays++;

				if((baseSet.GapDays() > 0) || gapDays > 5)
					break;
				//				break;
			}

			if((baseSet.UpdateDays() == 0) && (baseSet.GapDays() == 0)) {
				System.out.println("##########################################");
				System.out.println("best mean - variance : " + MV_gapDays_updateDays.firstKey());
				System.out.println("gap + updateDays : " + MV_gapDays_updateDays.get(MV_gapDays_updateDays.firstKey()));
				System.out.println("##########################################");
				ArrayList<Integer> gapAndUpdate = MV_gapDays_updateDays.get(MV_gapDays_updateDays.firstKey());
				baseSet.setGapDays(gapAndUpdate.get(0));
				baseSet.setUpdateDays(gapAndUpdate.get(1));

			}else if(!(baseSet.UpdateDays() == 0) && (baseSet.GapDays() == 0)) {

			}else if((baseSet.UpdateDays() == 0) && !(baseSet.GapDays() == 0)) {
				System.out.println("Given Gap Days, Auto Update Days");
			}else {
				System.out.println("Given Update & Gap Days");
			}

			//make arff file
			int run = 0;

			//default value
			String trS = baseSet.StartDate();
			String trE_gapS = null;//gap start
			String gapE_teS = null;//test start
			String teE = null;//test end
			
			String beforeTeE = null;

			ArrayList<Integer> tr_size = new ArrayList<>();
			ArrayList<Integer> te_size = new ArrayList<>();

			ArrayList<Float> tr_bugRatio = new ArrayList<>();
			ArrayList<Float> te_bugRatio = new ArrayList<>();

			ArrayList<RunDate> runDates = new ArrayList<>();

			while(!(teE != null) || !(baseSet.EndDate().compareTo(teE) <= 0)) { //end data가 teE보다 작지 않으면  

				//				System.out.println("T1 : "+T1);
				//cal training set end date (T2)
				TreeSet<String> tr_commitHash = new TreeSet<String>();
				int count = 0;
				for(String commitTime : commitTime_commitHash_experimental.keySet()) {
					if(!(trS.compareTo(commitTime)<=0))
						continue;

					TreeSet<String> commitHashs = commitTime_commitHash_experimental.get(commitTime);
					tr_commitHash.addAll(commitHashs);
					count += commitHashs.size();

					if(count == baseSet.Default_Tr_size()) {
						tr_size.add(count);
						trE_gapS = commitTime; //endDate
					}else if(count > baseSet.Default_Tr_size()) {
						tr_size.add(count - commitHashs.size());
						break;
					}
					trE_gapS = commitTime;
				}
				//				System.out.println("T2 : "+T2);

				//check TR bugRatio
				float bugRatio = calBuggyRatio(trS,trE_gapS,commitHash_isBuggy,commitTime_commitHash_experimental);

				if((bugRatio == 200) || (bugRatio == 0)) {
					trS = addDate(trS,baseSet.UpdateDays());
					trS = findNearDate(trS,commitTime_commitHash,"l");
					continue;
				}
				tr_bugRatio.add(bugRatio);

				//jump gap month
				gapE_teS = addMonth(trE_gapS,baseSet.GapDays());

				//				System.out.println("T3 : "+T3);

				//cal test
				TreeSet<String> te_commitHash = new TreeSet<String>();

				teE = addDate(gapE_teS,baseSet.UpdateDays());
				System.out.println("T4 : "+teE);
				
				if(beforeTeE != null && teE.compareTo(beforeTeE) == 0) {
					System.out.println("Error : "+baseSet.ProjectName());
					System.exit(0);
				}//exception error

				//check test bug ratio
				bugRatio = calBuggyRatio(gapE_teS,teE,commitHash_isBuggy,commitTime_commitHash_experimental);

				if((bugRatio == 200) || (bugRatio == 0)) {
					trS = addDate(trS,baseSet.UpdateDays());
					trS = findNearDate(trS,commitTime_commitHash,"l");
					continue;
				}
				te_bugRatio.add(bugRatio);

				//save tr data to arff
				save2Arff(run,tr_commitHash,commitHash_data,attributeLineList,directoryPath,"tr");

				//
				count = 0;
				for(String commitTime : commitTime_commitHash_experimental.keySet()) {
					if(!(gapE_teS.compareTo(commitTime)<=0 && commitTime.compareTo(teE)<0))
						continue;
					TreeSet<String> commitHashs = commitTime_commitHash_experimental.get(commitTime);
					te_commitHash.addAll(commitHashs);
					count += commitHashs.size();
				}
				te_size.add(count);

				//save tr data to arff
				save2Arff(run,te_commitHash,commitHash_data,attributeLineList,directoryPath,"te");

				//save information Ts
				RunDate runDate = new RunDate();
				runDate.setTrS(trS);
				runDate.setTrE_gapS(trE_gapS);
				runDate.setGapE_teS(gapE_teS);
				runDate.setTeE(teE);
				runDates.add(run,runDate);
				beforeTeE = teE;
				//update T1
				trS = addDate(trS,baseSet.UpdateDays());
				trS = findNearDate(trS,commitTime_commitHash,"l");
				run++;

			}

			//print result
			saveResult(runDates,tr_size,tr_bugRatio,te_size,te_bugRatio,directoryPath,run,baseSet);

			if(verbose) {
				System.out.println("Your program is terminated. (This message is shown because you turned on -v option!");
			}
		}
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private String[] extratOnlineargs(String arffPath, String directoryPath) {
		
		String[] extratPDPargs = new String[3];
		extratPDPargs[0] = arffPath;
		extratPDPargs[1] = directoryPath;
		extratPDPargs[2] = "o";
		
		return extratPDPargs;
	}
	
	
	private void saveResult(ArrayList<RunDate> runDates, ArrayList<Integer> tr_size, ArrayList<Float> tr_bugRatio,
			ArrayList<Integer> te_size, ArrayList<Float> te_bugRatio, String directoryPath, int run, BaseSetting baseSet2) throws Exception {
		String resultCSVPath = directoryPath + File.separator + "Run_Information.csv";
		BufferedWriter writer = new BufferedWriter(new FileWriter(resultCSVPath));
		CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("TrSt","TrEn_GapSt","TrCommit","TrBug%","GapEn_TeSt","TeEn","TeCommit","TeBug%"));
		for(int i = 0; i < run; i++) {
			RunDate runDate = runDates.get(i);
			csvPrinter.printRecord(runDate.getTrS(),runDate.getTrE_gapS(),tr_size.get(i),tr_bugRatio.get(i)*100,runDate.getGapE_teS(),runDate.getTeE(),te_size.get(i),te_bugRatio.get(i)*100);
		}
		csvPrinter.close();
		writer.close();

		BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(new File(directoryPath + File.separator + "Project_Information.txt")));

		bufferedWriter.write("total Commit : "+baseSet.TotalChange());
		bufferedWriter.write("\n");
		bufferedWriter.write("firstCommitTimeStr : "+baseSet.FirstCommitTimeStr());
		bufferedWriter.write("\n");
		bufferedWriter.write("lastCommitTimeStr : "+baseSet.LastCommitTimeStr());
		bufferedWriter.write("\n");
		bufferedWriter.write("Real startDate : "+baseSet.StartDate());
		bufferedWriter.write("\n");
		bufferedWriter.write("Real endDate : "+baseSet.EndDate());
		bufferedWriter.write("\n");
		bufferedWriter.write("total Experimental Commit : "+baseSet.TotalExperimentalCommit());
		bufferedWriter.write("\n");
		bufferedWriter.write("totalBuggyRatio% : "+baseSet.TotalBuggyRatio()*100);
		bufferedWriter.write("\n");
		bufferedWriter.write("Average Bug Fixing Time (Day): "+baseSet.averageBugFixingTimeDays);
		bufferedWriter.write("\n");
		bufferedWriter.write("gapDays (Month) : "+baseSet.GapDays());
		bufferedWriter.write("\n");
		bufferedWriter.write("updateDays (Day) : "+baseSet.UpdateDays());
		bufferedWriter.write("\n");
		bufferedWriter.close();

	}

	private void save2Arff(int run, TreeSet<String> tr_commitHash, HashMap<String, ArrayList<String>> commitHash_data,
			ArrayList<String> attributeLineList, String directoryPath, String string) throws Exception {
		File newDeveloperArff = new File(directoryPath +File.separator+run+"_"+string+".arff");
		StringBuffer newContentBuf = new StringBuffer();

		//write attribute
		for (String line : attributeLineList) {
			if(line.startsWith("@attribute meta_data-commitTime")) continue;
			if(line.startsWith("@attribute Key {")) continue;
			newContentBuf.append(line + "\n");
		}

		for(String commitHash : commitHash_data.keySet()) {
			if(tr_commitHash.contains(commitHash)) {
				for(String data : commitHash_data.get(commitHash)) {
					newContentBuf.append(data + "\n");
				}
			}
		}

		FileUtils.write(newDeveloperArff, newContentBuf.toString(), "UTF-8");

	}

	private String addMonth(String gap_startDate, int m) throws ParseException {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); 
		Calendar cal = Calendar.getInstance(); 
		Date date = format.parse(gap_startDate);
		cal.setTime(date); 
		//		cal.add(Calendar.YEAR, y); //년 더하기 
		cal.add(Calendar.MONTH, m); //년 더하기 
		//		cal.add(Calendar.DATE, d); //년 더하기 

		return format.format(cal.getTime());
	}

	private ArrayList<String> calEndDateNumOfCommit(String startDate, int numOfCommit,
			TreeMap<String, TreeSet<String>> commitTime_commitHash_experimental) {
		ArrayList<String> endDate_numOfCommit = new ArrayList<String>();
		int count = 0;
		for(String commitTime : commitTime_commitHash_experimental.keySet()) {
			if(!(startDate.compareTo(commitTime)<=0))
				continue;
			TreeSet<String> commitHashs = commitTime_commitHash_experimental.get(commitTime);
			count += commitHashs.size();

			if(count == numOfCommit) {
				System.out.println("Current count : "+count +"   numOfCommit : "+numOfCommit+"   count : "+ count);
				endDate_numOfCommit.add(1, Integer.toString(count));
				break; //current end Date
			}else if(count > numOfCommit) {
				System.out.println("Current count : "+count +"   numOfCommit : "+numOfCommit+"   count : "+ (count - commitHashs.size()));
				endDate_numOfCommit.add(1, Integer.toString(count - commitHashs.size()));
				return endDate_numOfCommit; //before endDate
			}
			endDate_numOfCommit.add(0, commitTime);
		}
		return endDate_numOfCommit;
	}

	private float calBuggyRatio(String startGapStr, String endGapStr,
			HashMap<String, ArrayList<Boolean>> commitHash_isBuggy, TreeMap<String, TreeSet<String>> commitTime_commitHash_experimental) {
		int buggyKey = 0;
		int totalKey = 0;

		for(String commitTime : commitTime_commitHash_experimental.keySet()) {
			if(!(startGapStr.compareTo(commitTime)<=0 && commitTime.compareTo(endGapStr)<=0))
				continue;
			TreeSet<String> commitHashs = commitTime_commitHash_experimental.get(commitTime);
			for(String commitHash : commitHashs) {
				ArrayList<Boolean> isBuggys = commitHash_isBuggy.get(commitHash);
				totalKey += isBuggys.size();
				for(boolean isBuggy : isBuggys) {
					if(isBuggy == true) buggyKey++;
				}
			}
		}

		if(totalKey != 0) {
			return (float)buggyKey / (float)totalKey;
		}else {
			return 200;
		}
	}

	private boolean parsingBugCleanLabel(String line, String firstLabel ,String defaultLabel) {
		if(line.startsWith(firstLabel)) {
			if(defaultLabel.compareTo("clean") == 0) 
				return false;
			else {
				return true;
			}
		}else {
			if(defaultLabel.compareTo("clean") == 0) 
				return true;
			else {
				return false;
			}
		}
	}

	private String findNearDate(String time, TreeMap<String, TreeSet<String>> commitTime_commitHash,
			String string) {
		if(string.compareTo("r") == 0) {
			for(String commitTime : commitTime_commitHash.keySet()) {
				if(!(time.compareTo(commitTime) < 0)) continue;
				return commitTime;
			}
		}else if(string.compareTo("l") == 0) {
			String beforeCommitTime = null;
			for(String commitTime : commitTime_commitHash.keySet()) {
				if(!(time.compareTo(commitTime) < 0)) {
					beforeCommitTime = commitTime;
					continue;
				}
				return beforeCommitTime;
			}
		}
		return null;
	}


	private String parsingCommitHash(String line, String firstKey, int indexOfKey) {
		String key = null;
		if((line.contains(","+indexOfKey+" "))) {
			key = line.substring(line.lastIndexOf(Integer.toString(indexOfKey)),line.lastIndexOf("}"));
			key = key.substring(key.lastIndexOf(" ")+1,key.length());
		}else {
			key = firstKey;
		}

		return key.substring(0,key.indexOf("-"));
	}

	private String parsingDataLine(String line, int indexOfCommitTime,int indexOfKey) {
		if((line.contains(","+indexOfKey+" "))) {
			if((line.contains(","+indexOfCommitTime+" "))) { //index previous,index commitTime, index key} 
				line = line.substring(0,line.lastIndexOf(","+indexOfCommitTime));
				line = line + "}";
				return line;
			}else {											//index previous,index key}
				line = line.substring(0,line.lastIndexOf(","+indexOfKey));
				line = line + "}";
				return line;
			}
		}else {
			if((line.contains(","+indexOfCommitTime+" "))) {//index previous,index commitTime} 
				line = line.substring(0,line.lastIndexOf(","+indexOfCommitTime));
				line = line + "}";
				return line;
			}else {											//index previous,index commitTime} 
				return line;
			}
		}
	}

	private String parsingCommitTime(String line, String firstCommitTime, int indexOfCommitTime) {
		if((line.contains(","+indexOfCommitTime+" "))) {
			String commitTime = line.substring(line.lastIndexOf(indexOfCommitTime+" '"),line.lastIndexOf("'"));
			commitTime = commitTime.substring(commitTime.lastIndexOf("'")+1,commitTime.length());
			return commitTime;
		}else {
			return firstCommitTime;
		}
	}

	private String addDate(String dt, int d) throws Exception  {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

		Calendar cal = Calendar.getInstance();
		Date date = format.parse(dt);
		cal.setTime(date);
		cal.add(Calendar.DATE, d);		//년 더하기

		return format.format(cal.getTime());

	}

	private static int calDateBetweenAandB(String preTime, String commitTime) throws Exception {

		SimpleDateFormat format = new SimpleDateFormat("yyyy-mm-dd");
		// date1, date2 두 날짜를 parse()를 통해 Date형으로 변환.

		Date FirstDate = format.parse(preTime);
		Date SecondDate = format.parse(commitTime);

		// Date로 변환된 두 날짜를 계산한 뒤 그 리턴값으로 long type 변수를 초기화 하고 있다.
		// 연산결과 -950400000. long type 으로 return 된다.
		long calDate = FirstDate.getTime() - SecondDate.getTime(); 

		// Date.getTime() 은 해당날짜를 기준으로1970년 00:00:00 부터 몇 초가 흘렀는지를 반환해준다. 
		// 이제 24*60*60*1000(각 시간값에 따른 차이점) 을 나눠주면 일수가 나온다.
		long calDateDays = calDate / ( 24*60*60*1000); 

		calDateDays = Math.abs(calDateDays);
		//        System.out.println(commitTime);
		//        System.out.println("두 날짜의 날짜 차이: "+calDateDays);

		return (int)calDateDays;

	}

	private boolean parseOptions(Options options, String[] args) {
		CommandLineParser parser = new DefaultParser();

		try {
			CommandLine cmd = parser.parse(options, args);
			dataPath = cmd.getOptionValue("i");
			BICpath = cmd.getOptionValue("b");
			String outputPath = cmd.getOptionValue("o");
			if(outputPath.endsWith(File.separator)) {
				outputPath = outputPath.substring(0, outputPath.lastIndexOf(File.separator));
				baseSet.setOutputPath(outputPath);
			}else {
				baseSet.setOutputPath(outputPath);
			}

			if(cmd.hasOption("s") && cmd.hasOption("e")) {
				baseSet.setStartDate(cmd.getOptionValue("s"));
				baseSet.setEndDate(cmd.getOptionValue("e"));
			}else if(cmd.hasOption("s") || cmd.hasOption("e")) {
				System.out.println("Please enter Start date and end date");
			}else if(!(cmd.hasOption("s") || cmd.hasOption("e"))){
				baseSet.setStartDate(null);
			}


			if(cmd.hasOption("u") && cmd.hasOption("g")) {
				baseSet.setUpdateDays(Integer.parseInt(cmd.getOptionValue("u")));
				baseSet.setGapDays(Integer.parseInt(cmd.getOptionValue("g")));
			}
			else if(cmd.hasOption("u") && !cmd.hasOption("g")) {
				baseSet.setUpdateDays(Integer.parseInt(cmd.getOptionValue("u")));
				baseSet.setGapDays(0);
			}
			else if(!cmd.hasOption("u") && cmd.hasOption("g")) {
				baseSet.setGapDays(Integer.parseInt(cmd.getOptionValue("g")));
				baseSet.setUpdateDays(0);
			}
			else if(!(cmd.hasOption("u") && cmd.hasOption("g"))){
				baseSet.setGapDays(0);
				baseSet.setUpdateDays(0);
			}

			help = cmd.hasOption("h");

		} catch (Exception e) {
			printHelp(options);
			return false;
		}

		return true;
	}

	private Options createOptions() {
		Options options = new Options();

		// add options by using OptionBuilder
		options.addOption(Option.builder("i").longOpt("metadata.arff")
				.desc("Address of meta data arff file. Don't use double quotation marks")
				.hasArg()
				.argName("URI")
				.required()
				.build());// 필수

		options.addOption(Option.builder("b").longOpt("BIC.csv")
				.desc("BIC file path. Don't use double quotation marks")
				.hasArg()
				.argName("path")
				.required()
				.build());

		options.addOption(Option.builder("o").longOpt("output")
				.desc("output path. Don't use double quotation marks")
				.hasArg()
				.argName("path")
				.required()
				.build());
		//options
		options.addOption(Option.builder("s").longOpt("startdate")
				.desc("Start date for collecting training data. Format: \"yyyy-MM-dd HH:mm:ss\"")
				.hasArg()
				.argName("Start date")
				.build());

		options.addOption(Option.builder("e").longOpt("enddate")
				.desc("End date for collecting test data. Format: \"yyyy-MM-dd HH:mm:ss\"")
				.hasArg()
				.argName("End date")
				.build());

		options.addOption(Option.builder("u").longOpt("updatedays")
				.desc("update date for collecting training data. Format: days")
				.hasArg()
				.argName("Update date")
				.build());

		options.addOption(Option.builder("g").longOpt("gapdays")
				.desc("gap date for collecting training data. Format: days")
				.hasArg()
				.argName("Gap date")
				.build());

		options.addOption(Option.builder("h").longOpt("help")
				.desc("Help")
				.build());

		return options;
	}

	private void printHelp(Options options) {
		// automatically generate the help statement
		HelpFormatter formatter = new HelpFormatter();
		String header = "Collecting developer Meta-data program";
		String footer = "\nPlease report issues at https://github.com/HGUISEL/DAISE/issues";
		formatter.printHelp("DAISE", header, options, footer, true);
	}

}


class BaseSetting {
	String outputPath;
	String projectName;
	String referenceFolderPath;
	int averageBugFixingTimeDays;
	String firstCommitTimeStr;
	String lastCommitTimeStr;
	String startDate;
	String endDate;
	int endGapDays;
	int totalExperimentalCommit; //commit
	float totalBuggyRatio; // commit - source key
	int updateDays; //days  test set duration days
	int gapDays; //days
	int default_Tr_size;
	int totalChange;

	BaseSetting(){
		projectName = null;
		referenceFolderPath = null;
		outputPath = null;
		averageBugFixingTimeDays = 0;
		firstCommitTimeStr = null;
		lastCommitTimeStr = null;
		startDate = null;
		endDate = null;
		endGapDays = 0;
		totalExperimentalCommit = 0;
		totalBuggyRatio = 0;
		gapDays = 0;
		updateDays = 0;
		default_Tr_size = 0;
		totalChange = 0;
	}
	public void setDataPath(String dataPath) {
		Pattern pattern = Pattern.compile("(.+)/(.+).arff");

		Matcher matcher = pattern.matcher(dataPath);
		while(matcher.find()) {
			this.referenceFolderPath = matcher.group(1);
			this.projectName = matcher.group(2);
		}

	}

	public String OutputPath() {
		return outputPath;
	}

	public void setOutputPath(String outputPath) {
		this.outputPath = outputPath;
	}

	public String FirstCommitTimeStr() {
		return firstCommitTimeStr;
	}
	public void setFirstCommitTimeStr(String firstCommitTime) {
		this.firstCommitTimeStr = firstCommitTime;
	}
	public String LastCommitTimeStr() {
		return lastCommitTimeStr;
	}
	public void setLastCommitTimeStr(String lastCommitTime) {
		this.lastCommitTimeStr = lastCommitTime;
	}
	public String ProjectName() {
		return projectName;
	}
	public void setProjectName(String projectName) {
		this.projectName = projectName;
	}
	public String ReferenceFolderPath() {
		return referenceFolderPath;
	}
	public void setReferenceFolderPath(String referenceFolderPath) {
		this.referenceFolderPath = referenceFolderPath;
	}
	public int AverageBugFixingTimeDays() {
		return averageBugFixingTimeDays;
	}
	public void setAverageBugFixingTimeDays(int averageBugFixingTimeDays) {
		this.averageBugFixingTimeDays = averageBugFixingTimeDays;
	}

	public int GapDays() {
		return gapDays;
	}

	public void setGapDays(int gapDays) {
		this.gapDays = gapDays;
	}

	public int UpdateDays() {
		return updateDays;
	}

	public void setUpdateDays(int updateDays) {
		this.updateDays = updateDays;
	}

	public String StartDate() {
		return startDate;
	}

	public void setStartDate(String startDate) {
		this.startDate = startDate;
	}

	public String EndDate() {
		return endDate;
	}

	public void setEndDate(String endDate) {
		this.endDate = endDate;
	}

	public int TotalExperimentalCommit() {
		return totalExperimentalCommit;
	}

	public void setTotalExperimentalCommit(int plusExperimentalCommit) {
		this.totalExperimentalCommit += plusExperimentalCommit;
	}

	public void resetTotalExperimentalCommit() {
		this.totalExperimentalCommit = 0;
	}
	public int EndGapDays() {
		return endGapDays;
	}
	public void setEndGapDays(int endGapDays) {
		this.endGapDays = endGapDays;
	}

	public float TotalBuggyRatio() {
		return totalBuggyRatio;
	}

	public void setTotalBuggyRatio(float totalBuggyRatio) {
		this.totalBuggyRatio = totalBuggyRatio;
	}
	public int Default_Tr_size() {
		return default_Tr_size;
	}
	public void setDefault_Tr_size(int default_Tr_size) {
		this.default_Tr_size = default_Tr_size;
	}
	public int TotalChange() {
		return totalChange;
	}
	public void setTotalChange(int totalChange) {
		this.totalChange = totalChange;
	}

}

class RunDate {
	String trS;
	String trE_gapS;
	String gapE_teS;
	String teE;

	RunDate(){
		trS = null;
		trE_gapS = null;
		gapE_teS = null;
		teE = null;
	}

	public String getTrS() {
		return trS;
	}

	public void setTrS(String t1) {
		trS = t1;
	}

	public String getTrE_gapS() {
		return trE_gapS;
	}

	public void setTrE_gapS(String t2) {
		trE_gapS = t2;
	}

	public String getGapE_teS() {
		return gapE_teS;
	}

	public void setGapE_teS(String t3) {
		gapE_teS = t3;
	}

	public String getTeE() {
		return teE;
	}

	public void setTeE(String t4) {
		teE = t4;
	}

}
