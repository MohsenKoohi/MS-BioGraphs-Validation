/*
	It is a parallel code for calculating SHASUM of 64 MB 
	edge blocks of MS-BioGraphs using the WebGraph library.
*/

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.NoSuchElementException;
import it.unimi.dsi.webgraph.*;
import it.unimi.dsi.webgraph.labelling.*;
import java.util.concurrent.atomic.*;
import java.text.SimpleDateFormat ;
import java.util.Date;
import java.security.*;

public class EdgeBlockSHA
{	
	private static int threads_count = 0;	
	private static String graph_file;
	private static String offsets_file;
	private static String output_file;
	private SimpleDateFormat df;

	private long block_size = 64 * 1024L * 1024;
	// private long block_size = 1024L;
	private int blocks_count;
	private BlockStart blocks_start[];
	private String endpoints_shas [];
	private String weights_shas [];
	private BitStreamArcLabelledImmutableGraph graph;
	private AtomicInteger last_block;
	private AtomicLong processed_edges;

	class BlockStart
	{
		public int vertex;
		public int edge_index;

		BlockStart(int v, int ei)
		{
			vertex = v;
			edge_index = ei;
		}
	}

	static public void main(String[] args)
	{	
		System.out.println("\n\033[1;32mSHA Calculator for MS-BioGraphs Arc-Labeled Graphs Using WebGraph Library\033[0;37m");
		assert args.length > 0;
		System.out.println("  graph_file (args[0]):  " + args[0]);
		System.out.println("  output_file (args[1]): " + args[1]);


		graph_file = args[0];
		output_file = args[1];
		offsets_file = graph_file + "_offsets.bin";
		System.out.println("  offsets_file:  " + offsets_file);

		new EdgeBlockSHA();

		// System.out.println("\n");		
		return;
	}

	private EdgeBlockSHA()
	{
		df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS");

		threads_count = Math.min(128, Runtime.getRuntime().availableProcessors());
		System.out.println("  threads_count: " + threads_count);

		try
		{
			// Reading the input graph
				long t0 = -System.nanoTime();
				graph = BitStreamArcLabelledImmutableGraph.loadMapped(graph_file + "-weights");
				System.out.format ("  Graph Init. Time: %,.2f seconds\n",(t0 + System.nanoTime())/1e9);
				System.out.println("  RandomAccess: " + graph.randomAccess());
				System.out.println("  Arc labeled: " + (graph instanceof ArcLabelledImmutableGraph ?"Yes":"No"));
				System.out.format ("  #Nodes: %,d\n", graph.numNodes());
				System.out.format ("  #Arcs: %,d\n", graph.numArcs());

				if(!graph.randomAccess())
				{
					System.out.println("The graph is not a random access graph. Use serial code.");
					return;
				}

				if(!(graph instanceof ArcLabelledImmutableGraph))
				{
					System.out.println("The graph is not a weighted graph.");
					return;	
				}

			// Iitializing the variables
				long long_blocks_count = (long)Math.ceil(1.0 * graph.numArcs() / block_size);
				assert long_blocks_count < Integer.MAX_VALUE;
				blocks_count = (int)long_blocks_count;

				endpoints_shas = new String[(int)blocks_count + 1];
				weights_shas = new String[(int)blocks_count + 1];
				blocks_start = new BlockStart[blocks_count + 1];

				System.out.format("  block_size:   %,d\n", block_size);
				System.out.format("  blocks_count: %,d\n", blocks_count);
				System.out.println();

			// Runnig threads
			ReaderThread rt[] = new ReaderThread[threads_count];

			// Step 0: We read the offsets_file and set the `blocks_start` - 
			// 			This step has been previously implemented in parallel mode to use WebGraph, but now it is sequential.
			// Step 1: Threads read edges and weights and calculate sha1
			
			for(int step = 0; step < 2; step++)
			{
				System.out.println(getTime() + "Step " + step + ": Started.");

				// Creating start points of partitions
				if(step == 0)
				{
					DataInputStream in = new DataInputStream(new FileInputStream(offsets_file));
					byte[] vals = new byte[8];
					ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

					blocks_start[0] = new BlockStart(0,0);
					int curret_block = 1;
					
					int ret = in.read(vals);
					assert ret == 8;
					long prev_offset = 0;

					for(int v = 1; v <= graph.numNodes(); v++)
					{
						ret = in.read(vals);
						assert ret == 8;
						long offset = buffer.rewind().put(vals).rewind().getLong();
						
						while(offset > block_size * curret_block)
						{
							long edge_index = block_size * curret_block - prev_offset;
							blocks_start[curret_block] = new BlockStart(v - 1, (int)edge_index);
							curret_block++;
						}

						prev_offset = offset;
					}

					assert curret_block == blocks_count;
					blocks_start[curret_block] = new BlockStart(graph.numNodes(), 0);

					buffer = null;
					in.close();
					
					System.out.println(getTime() + "Step " + step + ": Done.");	
					System.out.println(getTime() + "Step 0: Setting blocks' start points completed.");
				}

				if(step == 1)
				{
					last_block = new AtomicInteger(0);
					processed_edges = new AtomicLong(0);

					// Creating the output files
					for(int t=0; t<threads_count; t++)
					{
						rt[t] = new ReaderThread(t, step);
						rt[t].start();
					}

					for(int t=0; t<threads_count; t++)
						rt[t].join();
				
					System.out.println(getTime() + "Step " + step + ": Done.");

					// Writing SHAs to the file
					assert last_block.get() == blocks_count + threads_count;
					System.out.format("  processed_edges: %,d\n", processed_edges.get());
					assert processed_edges.get() == graph.numArcs();

					PrintWriter f = new PrintWriter(new FileWriter(output_file));
					f.printf("%10s; %10s; %10s; %40s; %40s;\n",
						block_size/1024/1024 + "MB blk#",
						"vertex",
						"edge index",
						"endpoint_sha",
						"weights_sha"
					);
					 
					for(int b = 0; b <= blocks_count; b++)
						f.printf("%10d; %10d; %10d; %40s; %40s;\n", 
							b, 
							blocks_start[b].vertex, 
							blocks_start[b].edge_index, 
							endpoints_shas[b],
							weights_shas[b]
						);
					f.flush();
					f.close();

					System.out.println(getTime() + "Step 1: Writing SHAs to files completed.");
				}

				System.out.println();
			}

			System.out.format("\n\tExec. Time for creating sha of edge blocks: %,.2f seconds\n",(t0 + System.nanoTime())/1e9);
		}
		catch(Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}

		return;
	}

	class ReaderThread extends Thread
	{
		private int step;
		private int tid;

		protected ReaderThread(int tid, int step)
		{
			this.tid = tid;
			this.step = step;

			return;
		}

		public void run()
		{
			try
			{
				if(step == 1)
				{
					BitStreamArcLabelledImmutableGraph g = graph.copy();
					ByteBuffer ib = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
					long pe = 0;

					while(true)
					{
						int block = last_block.getAndIncrement();
						if(block >= blocks_count)
							break;

						MessageDigest endpoints_md = MessageDigest.getInstance("SHA-1");
						MessageDigest weights_md = MessageDigest.getInstance("SHA-1");
						
						int start_vertex = blocks_start[block].vertex;
						int start_edge_index = blocks_start[block].edge_index;
						int end_vertex = blocks_start[block + 1].vertex;
						int end_edge_index = blocks_start[block + 1].edge_index;

						for(int v = start_vertex; v <= end_vertex; v++)
						{
							if(v == g.numNodes())
								break;

							long degree = g.outdegree((int)v);
							ArcLabelledNodeIterator.LabelledArcIterator it = g.successors((int)v);

							int n = 0;
							if(v == start_vertex)
								while(n < start_edge_index)
								{
									it.nextInt();
									n++;
								}

							int last_index = (int)degree;
							if(v == end_vertex)
								last_index = end_edge_index;

							while(n < last_index)
							{
								int dest = it.nextInt();
								endpoints_md.update(ib.rewind().putInt(dest).rewind().array());
								
								int label = it.label().getInt();
								weights_md.update(ib.rewind().putInt(label).rewind().array());

								n++;
								pe++;
							}
						}
						
						endpoints_shas[block] = toHexString(endpoints_md.digest());
						weights_shas[block] = toHexString(weights_md.digest());

						// if(block == 50)
						// 	System.out.println(endpoints_shas[block]);
					}

					processed_edges.addAndGet(pe);
				}
			}
			catch(Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}
	}

	private String toHexString(byte bytes[])
	{
		StringBuilder sb = new StringBuilder();
		for(int i=0; i< bytes.length ;i++)
			sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
		
		return sb.toString();	
	}

	private String getTime()
	{
		return "  \033[0;32m" + df.format(new java.util.Date())+ "\033[0;37m ";
	}
}