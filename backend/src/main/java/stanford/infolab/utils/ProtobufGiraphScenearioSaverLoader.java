package utils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.giraph.graph.Computation;
import org.apache.giraph.utils.ReflectionUtils;
import org.apache.giraph.utils.WritableUtils;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;

import com.google.protobuf.ByteString;

/**
 * An implementation of {@link IScenarioManager} to load and save data for Protocol Buffer scenario.
 * @author Brian Truong
 *
 * @param <I>   Vertex ID
 * @param <V>   Vertex value
 * @param <E>   Edge value
 * @param <M1>  Incoming message
 * @param <M2>  Outgoing message
 */
@SuppressWarnings("rawtypes")
public class ProtobufGiraphScenearioSaverLoader<I extends WritableComparable, V extends Writable, 
    E extends Writable, M1 extends Writable, M2 extends Writable> {

  @SuppressWarnings("unchecked")
  public ProtobufGiraphScenarioWrapper<ProtobufScenario<I, V, E, M1, M2>> load(String fileName)
      throws ClassNotFoundException, IOException {
    GiraphScenario giraphScenario = GiraphScenario.parseFrom(new FileInputStream(fileName));

    Class<?> clazz = Class.forName(giraphScenario.getClassUnderTest());
    Class<? extends Computation<I, V, E, M1, M2>> classUnderTest =
        (Class<? extends Computation<I, V, E, M1, M2>>) castClassToUpperBound(clazz,
            Computation.class);

    Class<I> vertexIdClass =
        (Class<I>) castClassToUpperBound(Class.forName(giraphScenario.getVertexIdClass()),
            WritableComparable.class);

    Class<V> vertexValueClass =
        (Class<V>) castClassToUpperBound(Class.forName(giraphScenario.getVertexValueClass()),
            Writable.class);

    Class<E> edgeValueClass =
        (Class<E>) castClassToUpperBound(Class.forName(giraphScenario.getEdgeValueClass()),
            Writable.class);

    Class<M1> incomingMessageClass =
        (Class<M1>) castClassToUpperBound(Class.forName(giraphScenario.getIncomingMessageClass()),
            Writable.class);

    Class<M2> outgoingMessageClass =
        (Class<M2>) castClassToUpperBound(Class.forName(giraphScenario.getOutgoingMessageClass()),
            Writable.class);

    ProtobufGiraphScenarioWrapper<ProtobufScenario<I, V, E, M1, M2>> scenarioList =
        new ProtobufGiraphScenarioWrapper<>(classUnderTest, vertexIdClass, vertexValueClass, edgeValueClass,
            incomingMessageClass, outgoingMessageClass);

    for (ScenarioOrBuilder protoBufScenario : giraphScenario.getScenarioOrBuilderList()) {
      ProtobufScenario<I, V, E, M1, M2> scenario = new ProtobufScenario<>();
      I vertexId = newInstance(vertexIdClass);
      fromByteString(protoBufScenario.getVertexId(), vertexId);
      scenario.setVertexId(vertexId);

      V vertexValue = newInstance(vertexValueClass);
      fromByteString(protoBufScenario.getVertexValue(), vertexValue);
      scenario.setVertexValue(vertexValue);

      for (int i = 0; i < protoBufScenario.getMessageCount(); i++) {
        M1 msg = newInstance(incomingMessageClass);
        fromByteString(protoBufScenario.getMessage(i).getMsgData(), msg);
        scenario.addIncomingMessage(msg);
      }

      for (Neighbor neighbor : protoBufScenario.getNeighborList()) {
        I neighborId = newInstance(vertexIdClass);
        fromByteString(neighbor.getNeighborId(), neighborId);

        E edgeValue;
        if (neighbor.hasEdgeValue()) {
          edgeValue = newInstance(edgeValueClass);
          fromByteString(neighbor.getEdgeValue(), edgeValue);
        } else {
          edgeValue = null;
        }
        scenario.addNeighbor(neighborId, edgeValue);

        for (int i = 0; i < neighbor.getMsgCount(); i++) {
          M2 msg = newInstance(outgoingMessageClass);
          fromByteString(neighbor.getMsg(i).getMsgData(), msg);
          scenario.addOutgoingMessage(neighborId, msg);
        }
      }
      scenarioList.addScenario(scenario);
    }
    return scenarioList;
  }

  public void save(String fileName, ProtobufGiraphScenarioWrapper<ProtobufScenario<I, V, E, M1, M2>> scenarioList)
      throws IOException {
    GiraphScenario.Builder giraphScenarioBuilder = GiraphScenario.newBuilder();
    Scenario.Builder scenarioBuilder = Scenario.newBuilder();
    Neighbor.Builder neighborBuilder = Neighbor.newBuilder();
    OutMsg.Builder outMsgBuilder = OutMsg.newBuilder();
    InMsg.Builder inMsgBuilder = InMsg.newBuilder();

    giraphScenarioBuilder.clear();
    giraphScenarioBuilder.setClassUnderTest(scenarioList.getClassUnderTest().getName());
    giraphScenarioBuilder.setVertexIdClass(scenarioList.getVertexIdClass().getName());
    giraphScenarioBuilder.setVertexValueClass(scenarioList.getVertexValueClass().getName());
    giraphScenarioBuilder.setEdgeValueClass(scenarioList.getEdgeValueClass().getName());
    giraphScenarioBuilder.setIncomingMessageClass(scenarioList.getIncomingMessageClass().getName());
    giraphScenarioBuilder.setOutgoingMessageClass(scenarioList.getOutgoingMessageClass().getName());

    for (ProtobufScenario<I, V, E, M1, M2> scenario : scenarioList.getScenarios()) {
      scenarioBuilder.clear();
      scenarioBuilder.setVertexId(toByteString(scenario.getVertexId())).setVertexValue(
          toByteString(scenario.getVertexValue()));

      for (I neighborId : scenario.getNeighbors()) {
        neighborBuilder.clear();
        neighborBuilder.setNeighborId(toByteString(neighborId));
        E edgeValue = scenario.getEdgeValue(neighborId);
        if (edgeValue != null) {
          neighborBuilder.setEdgeValue(toByteString(edgeValue));
        } else {
          neighborBuilder.clearEdgeValue();
        }
        for (M2 msg : scenario.getOutgoingMessages(neighborId)) {
          outMsgBuilder.setMsgData(toByteString(msg));
          neighborBuilder.addMsg(outMsgBuilder);
        }
        scenarioBuilder.addNeighbor(neighborBuilder);
      }

      for (M1 msg : scenario.getIncomingMessages()) {
        inMsgBuilder.setMsgData(toByteString(msg));
        scenarioBuilder.addMessage(inMsgBuilder);
      }
      giraphScenarioBuilder.addScenario(scenarioBuilder.build());
    }

    GiraphScenario giraphScenario = giraphScenarioBuilder.build();
    try (FileOutputStream output = new FileOutputStream(fileName)) {
      giraphScenario.writeTo(output);
    }
  }

  @SuppressWarnings("unchecked")
  protected <U> Class<U> castClassToUpperBound(Class<?> clazz, Class<U> upperBound) {
    if (!upperBound.isAssignableFrom(clazz)) {
      throw new IllegalArgumentException("The class " + clazz.getName() + " is not a subclass of "
          + upperBound.getName());
    }
    return (Class<U>) clazz;
  }
  
  private static void fromByteString(ByteString byteString, Writable writable) {
    if (writable != null) {
      WritableUtils.readFieldsFromByteArray(byteString.toByteArray(), writable);
    }
  }

  private static ByteString toByteString(Writable writable) {
    return ByteString.copyFrom(WritableUtils.writeToByteArray(writable));
  }
  
  private static <T> T newInstance(Class<T> theClass) {
    return NullWritable.class.isAssignableFrom(theClass) 
        ? null : ReflectionUtils.newInstance(theClass);
  }
}

/**
 * @author Brian Truong
 */
@SuppressWarnings("rawtypes")
class ProtobufScenario<I extends WritableComparable, V extends Writable, E extends Writable, 
    M1 extends Writable, M2 extends Writable> {

  private I vertexId;
  private V vertexValue;
  private ArrayList<M1> inMsgs;
  private Map<I, Nbr> outNbrMap;

  public ProtobufScenario() {
    reset();
  }
  
  void reset() {
    this.vertexId = null;
    this.vertexValue = null;
    this.inMsgs = new ArrayList<>();
    this.outNbrMap = new HashMap<>();
  }

  private void checkLoaded(Object arg) {
    if (arg == null) {
      throw new IllegalStateException("The ProtoBuf scenario has not been loaded or initialized.");
    }
  }

  public I getVertexId() {
    return vertexId;
  }

  public void setVertexId(I vertexId) {
    this.vertexId = vertexId;
  }

  public V getVertexValue() {
    return vertexValue;
  }

  public void setVertexValue(V vertexValue) {
    this.vertexValue = vertexValue;
  }

  public Collection<M1> getIncomingMessages() {
    return inMsgs;
  }

  public void addIncomingMessage(M1 message) {
    inMsgs.add(message);
  }

  public Collection<I> getNeighbors() {
    return outNbrMap.keySet();
  }

  public void addNeighbor(I neighborId, E edgeValue) {
    if (outNbrMap.containsKey(neighborId)) {
      outNbrMap.get(neighborId).edgeValue = edgeValue;
    } else {
      outNbrMap.put(neighborId, new Nbr(edgeValue));
    }
  }

  public E getEdgeValue(I neighborId) {
    checkLoaded(outNbrMap);
    Nbr nbr = outNbrMap.get(neighborId);
    return nbr == null ? null : nbr.edgeValue;
  }

  public void setEdgeValue(I neighborId, E edgeValue) {
    if (outNbrMap.containsKey(neighborId)) {
      outNbrMap.get(neighborId).edgeValue = edgeValue;
    } else {
      outNbrMap.put(neighborId, new Nbr(edgeValue));
    }
  }

  public Collection<M2> getOutgoingMessages(I neighborId) {
    Nbr nbr = outNbrMap.get(neighborId);
    return nbr == null ? null : nbr.msgs;
  }

  public void addOutgoingMessage(I neighborId, M2 msg) {
    if (!outNbrMap.containsKey(neighborId)) {
      outNbrMap.put(neighborId, new Nbr(null));
    }
    outNbrMap.get(neighborId).msgs.add(msg);
  }

  /**
   * @author Brian Truong
   */
  private class Nbr {
    private E edgeValue;
    private ArrayList<M2> msgs;

    public Nbr(E edgeValue) {
      this(edgeValue, new ArrayList<M2>());
    }

    public Nbr(E edgeValue, ArrayList<M2> msgs) {
      this.edgeValue = edgeValue;
      this.msgs = msgs;
    }
  }
}
