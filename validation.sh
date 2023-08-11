#!/bin/sh 

# Initializations
	JLIBS_FOLDER="jlibs"
	LC_NUMERIC="en_GB.UTF-8"
	__t0=`date +%s%N`
	echo -e "\n*** MS-Biographs Validation Script ***\n"

# Checking the arguments
	if [ -z $1 ]; then
		echo "Usage:"
		echo -e "\t\tvlidation.sh path/to/graph/file"
		echo -e "\n"
		echo -e "Note:"
		echo -e "\t(1) The first argument, 'path/to/graph/file', does not have suffix."
		echo -e "\tE.g., for 'MS1' graph use 'path/to/graph/folder/MS1'."
		echo -e "\tThe script considers all 'path/to/graph/folder/MS1*' files, including MS1-underlying.* and MS1-weights.*"
		echo -e "\n"
		echo -e "\t(2) This script downloads the compiled Java libraries of WebGraph from a third party repo."
		echo -e "\tThe source code can be retreived from: https://github.com/vigna/webgraph ."
		echo -e "\tThe latest version of JAR files can also be retrieved from https://search.maven.org/search?q=it.unimi.dsi ."
		echo -e "\n"
		exit -1
	fi

	graph_path=$1
	graph_path=`echo $graph_path | sed 's/-underlying//'`
	echo "path: $graph_path"
	graph_name=${graph_path##*/}
	echo "graph_name: $graph_name"
	names=("MS","MS200","MS50","MS1","MSA500","MSA200","MSA50","MSA10");
	if printf '%s\0' "${names[@]}" | grep -Fxz -- '$graph_name'; then
		echo "Error. ${graph_name} is not valid."
		exit -2
	fi

# Checking if the file exists
	if [ ! -f "${graph_path}.ojson" ]; then 
		echo "Error. File ${graph_path}.ojson does not exist."
		exit -3 
	fi

# Retrieving the shasum of the ojson file and matching it
	echo -e "\nChecking shasum of ${graph_name}.ojson (${graph_path}.ojson)"
	ojson_shasum=`wget "http://78.46.92.120/MS-BioGraphs/${graph_name}/ojson_shasum" -q -O -`
	if [ -z $ojson_shasum ]; then 
		echo "Error. Cannot retrieve data."
		exit -4 
	fi

	echo "ojson shasum: "$ojson_shasum
	local_shasum=`shasum "${graph_path}.ojson" | cut -f1 -d" "`
	echo "local shasum: "$local_shasum
	if [ $local_shasum !=  $ojson_shasum ]; then
		echo "shasum does not match."
		exit -5;
	fi;
	echo -e "shasum matches.\n"

	echo "JSON:"
	json=`cat "${graph_path}.ojson"`'}' 
	echo $json | jq .
	echo ""

# Checking shasum of files
	for f in "offsets" "wcc" "n2o" "trans_offsets" "edges_shas"; do
		file_name="${f}.bin"
		if [ $f == "edges_shas" ]; then 
			file_name="${f}.txt"
		fi
		echo -e "\nChecking file ${file_name}":

		if [ -f ${graph_path}-$file_name ]; then
			file_path="${graph_path}-${file_name}"
		else
			if [ -f ${graph_path}_$file_name ]; then
				file_path="${graph_path}_${file_name}"
			else
				echo -e "File does not exist.\n"
				continue
			fi
		fi
		echo -e "file-path: ${file_path}"

		file_shasum=`echo $json| jq -r ".${f}_ver_1_shasum"`
		echo "json shasum:  "$file_shasum

		local_shasum=`shasum "${file_path}" | cut -f1 -d" "`
		echo "local shasum: "$local_shasum

		if [ $local_shasum !=  $file_shasum ]; then
			echo "shasum does not match."
			exit -6;
		fi;
		echo -e "shasum matches.\n"
		 
	done

	echo -e "\n*** Files' shasum verified. ***\n"
	
# Downloading the WebGraph Java libraries
	echo -e "\nJLIBS_FOLDER: ${JLIBS_FOLDER}"
	if [ ! -d ${JLIBS_FOLDER} ]; then
		wget "http://78.46.92.120/MS-BioGraphs/jlibs.zip"
		if  [ $? != 0 ]; then
			echo "Error in downloading jlibs"
			exit -7;
		fi

		unzip jlibs.zip 
		rm jlibs.zip
		if [ "jlibs" != "$JLIBS_FOLDER" ]; then
			mv jlibs $JLIBS_FOLDER
		fi
		echo "Java libararies downloaded."
	fi

# Validating edge blocks 
	javac -cp ${JLIBS_FOLDER}/*:. EdgeBlockSHA.java
	if [ $? != 0 ]; then
		echo -e "\n\nCould not compile the java code.\n"
		exit -8
	fi
	
	java -ea -cp ${JLIBS_FOLDER}/*:. EdgeBlockSHA $graph_path ${graph_name}_local_edges_shas.txt 
	if [ $? != 0 ]; then
		echo -e "\n\nCould not run the java code.\n"
		exit -9
	fi

	diff ${graph_path}_edges_shas.txt ${graph_name}_local_edges_shas.txt
	if [ $? != 0 ]; then
		echo -e "\n\nThe shasums of edge blocks does not match .\n"
		exit -10
	fi

	echo -e "\n\nThe shasums of edge blocks matches.\n"
	rm ${graph_name}_local_edges_shas.txt

# Finalization
	__t1=`date +%s%N`
	__t0=`echo "scale=3;($__t1 - $__t0)/(1000*1000)" | bc`
	printf "Total exec. time: %'.3f (ms)\n\n" $__t0